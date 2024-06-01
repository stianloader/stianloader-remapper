package org.stianloader.remapper;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ModuleNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Simple in-memory remapping engine. Unlike many other remappers it is able to take in already parsed
 * {@link org.objectweb.asm.tree.ClassNode Objectweb ASM Classnodes} as input and output them without having
 * to go through an intermediary store-to-file mode.
 *
 * <p>Additionally, this is a remapper that is only a remapper. More specifically, it will only remap - but not
 * change your access flags, LVT entries or anything else that might not be intuitive.
 *
 * <p>The names of the destination namespace (or the remapped names in laymen's terms) are provided by the
 * {@link MappingLookup} instance supplied through the {@link Remapper#Remapper(MappingLookup) constructor}
 * of this class. After construction, the remapper itself cannot be mutated and all changes need to be performed
 * through the {@link MappingLookup} instance.
 *
 * <p>ClassNodes can be remapped via {@link Remapper#remapNode(ClassNode, StringBuilder)}. A similar method also exists
 * to remap MethodNodes and FieldNodes.
 *
 * <h2>Thread safety and concurrency</h2>
 *
 * <p>While a single instance of this class can be used in a concurrent environment and be shared across multiple threads,
 * the same may not apply to a {@link MappingLookup} instance. On a similar note a single call to the remapNode
 * methods do not cause any parallelisation to happen. As such if it is known that the remapper is called on larger
 * classes, it might be useful to be aware that methods can be individually remapped via
 * {@link Remapper#remapNode(String, MethodNode, StringBuilder)}. However, {@link Remapper#remapNode(ClassNode, StringBuilder)}
 * implies {@link Remapper#remapNode(String, MethodNode, StringBuilder)} so this strategy has serious flaws.
 * If a serious performance updraft is expected when employing parallelisation on a ClassNode level, then please
 * open up an issue on the project's repository.
 *
 * <h2>Method overloading, inheritance and other remapping restrictions</h2>
 *
 * <p>The burden of handling restrictions on method overloading and inheritance falls
 * upon the used {@link MappingLookup} instance. If the {@link MappingLookup} instance is erroneously implemented
 * or used (i.e. in the case of {@link SimpleMappingLookup} the instance being fed invalid data), it is possible to
 * modify the way inheritance and overloading behaves - potentially causing a method to no longer override another
 * or creating an override where no one existed beforehand. More acutely, it is also possible that inheritance applies
 * to fields, too - this is notably the case when making use of anonymous classes at enum members. Unlike sl-deobf,
 * <b>the burden of handling inheritance for fields also falls upon the {@link MappingLookup} instance</b>.
 *
 * <p>The {@link Remapper} instance is unable to verify mapping collisions and it is the job of the {@link MappingLookup}
 * implementation to ensure that such events do not happen - note that some implementations such as {@link SimpleMappingLookup}
 * do not check for such inconsistencies; for more information on this topic, see the manual of your lookup implementation.
 *
 * <p>While the {@link Remapper} instance allows nonsensical remapping requests (such as remapping methods from/to &lt;clinit&gt;
 * or &lt;init&gt;), it is imperative that this behaviour is not relied on and that {@link MappingLookup} instances take
 * the necessary precautions to prohibit such requests.
 *
 * <p>Layered mappings, that is mappings that are built ontop of other mappings, are not directly supported by this remapper.
 * However, it is possible to easily obtain this behaviour by calling the remapNode methods multiple times - more specifically
 * once per layer of mappings. That being said, some {@link MappingLookup} instances might not necessarily support such behaviour,
 * especially when it comes to computing the hierarchy of classes as the class name might not necessarily be known to the
 * {@link MappingLookup} instance.
 * As such the alternative is directly "squashing" the mapping layers into a single {@link MappingLookup}. In case of doubt,
 * consult the manual of the used lookup implementation for further guidance on how layered mappings may be implemented.
 *
 * <h2>Reflection</h2>
 *
 * <p>Remapping reflective calls are not supported due to the complexity required for such a niche feature.
 * If absolutely needed (we generally recommend wrapping the reflective operations in a way that they are redirected as needed
 * at runtime), 3rd party tools should be used. The same applies to method handles or other string constants. That being said,
 * class constants will get remapped so very simple reflective operations might still behave as intended.
 */
public final class Remapper {

    /**
     * Remaps a field descriptor.
     *
     * @param lookup The {@link MappingLookup} to use in order to remap the descriptor.
     * @param fieldDesc The old (unmapped) field descriptor
     * @param sharedBuilder A shared cached string builder. The contents of the string builder are wiped and after the invocation the contents are undefined
     * @return The new (remapped) field descriptor. It <b>can</b> be identity identical to the "fieldDesc" if it didn't need to be altered
     */
    @SuppressWarnings("null")
    @NotNull
    public static String getRemappedFieldDescriptor(@NotNull MappingLookup lookup, @NotNull String fieldDesc, @NotNull StringBuilder sharedBuilder) {
        sharedBuilder.setLength(0);
        return Remapper.remapSingleDesc(lookup, fieldDesc, sharedBuilder);
    }

    /**
     * Remaps a method descriptor.
     *
     * <p>Note: This method completely disregards bridges or other context-specific circumstances.
     * Overall, it aims to be the most generically applicable method.
     *
     * <p>Although this method was initially written to remap {@link MethodNode#desc method descriptors},
     * this method also can work with {@link MethodNode#signature method signatures}.
     *
     * @param lookup The {@link MappingLookup} to use in order to remap the descriptor.
     * @param methodDesc The old (unmapped) method descriptor
     * @param sharedBuilder A shared cached string builder. The contents of the string builder are wiped and after the invocation the contents are undefined
     * @return The new (remapped) method descriptor. It <b>can</b> be identity identical to the "methodDesc" if it didn't need to be altered
     */
    @NotNull
    public static String getRemappedMethodDescriptor(@NotNull MappingLookup lookup, @NotNull String methodDesc, @NotNull StringBuilder sharedBuilder) {
        sharedBuilder.setLength(0);
        if (!Remapper.remapSignature(lookup, methodDesc, sharedBuilder)) {
            return methodDesc;
        }
        return sharedBuilder.toString();
    }

    /**
     * Remap an internal name or array {@link String}, meaning that this method accepts the same kind
     * of strings as {@link Type#getObjectType(String)}.
     *
     * <p>The contents of the {@link StringBuilder} instance passed to this method might be overwritten and
     * the contents afterwards should be considered unknown. It is especially not guaranteed (in fact, it usually won't be)
     * that the content of the {@link StringBuilder} is equal to the returned {@link String}.
     *
     * @param lookup The {@link MappingLookup} to use in order to remap the descriptor.
     * @param internalName The internal name in the source namespace.
     * @param sharedStringBuilder A shared {@link StringBuilder} instance of object pooling purposes (note: The instance should not be used across multiple threads!)
     * @return The remapped internal name in the destination namespace.
     * @see Type#getInternalName()
     */
    @NotNull
    public static String remapInternalName(@NotNull MappingLookup lookup, @NotNull String internalName, @NotNull StringBuilder sharedStringBuilder) {
        if (internalName.codePointAt(0) == '[') {
            return Remapper.remapSingleDesc(lookup, internalName, sharedStringBuilder);
        } else {
            return lookup.getRemappedClassName(internalName);
        }
    }

    /**
     * Remap a generic signature string, as used for example in {@link MethodNode#signature}, {@link FieldNode#signature}
     * or {@link ClassNode#signature}. As this method is fairly generic it is even capable of remapping method, field or
     * type descriptors. However, this method is not capable of remapping internal names. If internal names should
     * be remapped, use {@link #remapInternalName(MappingLookup, String, StringBuilder)} instead.
     *
     * <p>Internally, this method is recursive (in order to be able to correctly remap nested generics), which is
     * why this method accepts a start and end pointer, which are the {@link String#codePointAt(int) codepoints}
     * which should be remapped and pushed to the {@link StringBuilder} buffer. This algorithm evaluates the
     * input signature from left to right.
     *
     * <p>In the case that end is greater than <code>start</code>, a crash is likely, although the type of crash is not defined.
     * It may also deadlock or cause an {@link OutOfMemoryError OOM situation}. Furthermore, if <code>end</code> does not correctly
     * align with a type boundary (usually a semicolon or a character that represents a primitive), then unexpected
     * behaviour is likely - more likely than not it will cause a crash, deadlock or {@link OutOfMemoryError}.
     * As similar behaviour also applies to <code>start</code>, both <code>start</code> and <code>end</code> should be chosen carefully.
     * More often than not, this method can be considered overkill and instead {@link Remapper#remapSignature(MappingLookup, String, StringBuilder)}
     * can be used safely as an alternative - however that method will remap the entire signature while
     * this method can (if <code>start</code> and <code>end</code> are chosen accordingly) remap parts of it.
     *
     * @param lookup The {@link MappingLookup} to use in order to remap the descriptor.
     * @param signature The signature to remap in the source namespace.
     * @param start The start of signature.
     * @param end The last codepoint of the signature that should be handled by this method. Everything beyond it is plainly ignored.
     * @param signatureOut The {@link StringBuilder} instance to which the remapped signature should be stored into.
     * @return True if a modification happened while remapping the signature, false otherwise.
     */
    public static boolean remapSignature(@NotNull MappingLookup lookup, @NotNull String signature, int start, int end, @NotNull StringBuilder signatureOut) {
        if (start == end) {
            return false;
        }
        int type = signature.codePointAt(start++);
        switch (type) {
        case 'T':
            // generics type parameter
            // fall-through intended as they are similar enough in format compared to objects
        case 'L':
            // object
            // find the end of the internal name of the object
            int endObject = start;
            while(true) {
                // this will skip a character, but this is not interesting as class names have to be at least 1 character long
                int codepoint = signature.codePointAt(++endObject);
                if (codepoint == ';') {
                    String name = signature.substring(start, endObject);
                    String newName = lookup.getRemappedClassNameFast(name);
                    boolean modified = false;
                    if (newName != null) {
                        name = newName;
                        modified = true;
                    }
                    signatureOut.appendCodePoint(type);
                    signatureOut.append(name);
                    signatureOut.append(';');
                    modified |= Remapper.remapSignature(lookup, signature, ++endObject, end, signatureOut);
                    return modified;
                } else if (codepoint == '<') {
                    // generics - please no
                    // post scriptum: well, that was a bit easier than expected
                    int openingBrackets = 1;
                    int endGenerics = endObject;
                    while(true) {
                        codepoint = signature.codePointAt(++endGenerics);
                        if (codepoint == '>' ) {
                            if (--openingBrackets == 0) {
                                break;
                            }
                        } else if (codepoint == '<') {
                            openingBrackets++;
                        }
                    }
                    String name = signature.substring(start, endObject);
                    String newName = lookup.getRemappedClassNameFast(name);
                    boolean modified = false;
                    if (newName != null) {
                        name = newName;
                        modified = true;
                    }
                    signatureOut.append('L');
                    signatureOut.append(name);
                    signatureOut.append('<');
                    modified |= Remapper.remapSignature(lookup, signature, endObject + 1, endGenerics++, signatureOut);
                    signatureOut.append('>');
                    // apparently that can be rarely be a '.', don't ask when or why exactly this occurs
                    signatureOut.appendCodePoint(signature.codePointAt(endGenerics));
                    modified |= Remapper.remapSignature(lookup, signature, ++endGenerics, end, signatureOut);
                    return modified;
                }
            }
            /*
        case '+':
            // I do not know what this one does - but it appears that it works good just like it does right now
        case '*':
            // wildcard - this can also be read like a regular primitive
            // fall-through intended
        case '(':
        case ')':
            // apparently our method does not break even in these cases, so we will consider them raw primitives
        case '[':
            // array - fall through intended as in this case they behave the same
             */
        default:
            // primitive
            signatureOut.appendCodePoint(type);
            return Remapper.remapSignature(lookup, signature, start, end, signatureOut); // Did not modify the signature - but following operations could
        }
    }

    /**
     * Remap a generic signature string, as used for example in {@link MethodNode#signature}, {@link FieldNode#signature}
     * or {@link ClassNode#signature}. As this method is fairly generic it is even capable of remapping method, field or
     * type descriptors. However, this method is not capable of remapping internal names. If internal names should
     * be remapped, use {@link #remapInternalName(MappingLookup, String, StringBuilder)} instead.
     *
     * <p>Internally, this method is recursive (in order to be able to correctly remap nested generics).
     * This algorithm evaluates the input signature from left to right.
     *
     * @param lookup The {@link MappingLookup} to use in order to remap the descriptor.
     * @param signature The signature to remap in the source namespace.
     * @param out The {@link StringBuilder} instance to which the remapped signature should be stored into.
     * @return True if a modification happened while remapping the signature, false otherwise - that is if false,
     * {@link StringBuilder#toString()} of <code>out</code> will be equal to <code>signature</code>.
     */
    public static boolean remapSignature(@NotNull MappingLookup lookup, @NotNull String signature, @NotNull StringBuilder out) {
        return Remapper.remapSignature(lookup, signature, 0, signature.length(), out);
    }

    @NotNull
    private static String remapSingleDesc(@NotNull MappingLookup lookup, @NotNull String input, StringBuilder sharedBuilder) {
        int indexofL = input.indexOf('L');
        if (indexofL == -1) {
            return input; // Primitive or array of primitives
        }
        int length = input.length();
        String internalName = input.substring(indexofL + 1, length - 1);
        String newInternalName = lookup.getRemappedClassNameFast(internalName);
        if (newInternalName == null) {
            return input;
        }
        sharedBuilder.setLength(indexofL + 1);
        sharedBuilder.setCharAt(indexofL, 'L');
        while(indexofL != 0) {
            sharedBuilder.setCharAt(--indexofL, '[');
        }
        sharedBuilder.append(newInternalName);
        sharedBuilder.append(';');
        return sharedBuilder.toString();
    }

    @NotNull
    private final MappingLookup lookup;

    /**
     * Constructor. Create a {@link Remapper} instance which uses a given {@link MappingLookup} instance
     * to remap nodes.
     *
     * @param lookup The lookup to use for all remapping requests, may not be null
     */
    public Remapper(@NotNull MappingLookup lookup) {
        this.lookup = lookup;
    }

    /**
     * Obtain the {@link MappingLookup} instance from which this {@link Remapper} sources all source to destination
     * namespace name mappings. This instance is set through the constructor.
     *
     * @return The {@link MappingLookup} instance used by this {@link Remapper}.
     */
    @NotNull
    @Contract(pure = true)
    public final MappingLookup getLookup() {
        return this.lookup;
    }

    private void remapAnnotation(AnnotationNode annotation, StringBuilder sharedStringBuilder) {
        String internalName = annotation.desc.substring(1, annotation.desc.length() - 1);
        String newInternalName = this.lookup.getRemappedClassNameFast(internalName);
        if (newInternalName != null) {
            annotation.desc = 'L' + newInternalName + ';';
        }
        if (annotation.values != null) {
            int size = annotation.values.size();
            for (int i = 0; i < size; i++) {
                @SuppressWarnings("unused") // We are using the cast as a kind of built-in automatic unit test
                String bitvoid = (String) annotation.values.get(i++);
                this.remapAnnotationValue(annotation.values.get(i), i, annotation.values, sharedStringBuilder);
            }
        }
    }

    private void remapAnnotations(List<? extends AnnotationNode> annotations, StringBuilder sharedStringBuilder) {
        if (annotations == null) {
            return;
        }
        for (AnnotationNode annotation : annotations) {
            this.remapAnnotation(annotation, sharedStringBuilder);
        }
    }

    private void remapAnnotationValue(Object value, int index, List<Object> values, StringBuilder sharedStringBuilder) {
        if (value instanceof Type) {
            String type = ((Type) value).getDescriptor();
            sharedStringBuilder.setLength(0);
            if (Remapper.remapSignature(this.lookup, type, sharedStringBuilder)) {
                values.set(index, Type.getType(sharedStringBuilder.toString()));
            }
        } else if (value instanceof String[]) {
            String[] enumvals = (String[]) value;
            String ownerName = enumvals[0].substring(1, enumvals[0].length() - 1);
            enumvals[1] = this.lookup.getRemappedFieldName(ownerName, enumvals[1], enumvals[0]);
            String newInternalName = this.lookup.getRemappedClassNameFast(ownerName);
            if (newInternalName != null) {
                enumvals[0] = 'L' + newInternalName + ';';
            }
        } else if (value instanceof AnnotationNode) {
            this.remapAnnotation((AnnotationNode) value, sharedStringBuilder);
        } else if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> valueList = (List<Object>) value;
            int i = valueList.size();
            while (i-- != 0) {
                this.remapAnnotationValue(valueList.get(i), i, valueList, sharedStringBuilder);
            }
        } else {
            // Irrelevant
        }
    }

    private void remapBSMArg(final Object[] bsmArgs, final int index, final StringBuilder sharedStringBuilder) {
        Object bsmArg = bsmArgs[index];
        if (bsmArg instanceof Type) {
            Type type = (Type) bsmArg;
            sharedStringBuilder.setLength(0);

            if (type.getSort() == Type.METHOD) {
                if (Remapper.remapSignature(this.lookup, type.getDescriptor(), sharedStringBuilder)) {
                    bsmArgs[index] = Type.getMethodType(sharedStringBuilder.toString());
                }
            } else if (type.getSort() == Type.OBJECT) {
                String oldVal = type.getInternalName();
                String remappedVal = Remapper.remapInternalName(this.lookup, oldVal, sharedStringBuilder);
                if (oldVal != remappedVal) { // Instance comparison intended
                    bsmArgs[index] = Type.getObjectType(remappedVal);
                }
            } else {
                throw new IllegalArgumentException("Unexpected bsm arg Type sort. Sort = " + type.getSort() + "; type = " + type);
            }
        } else if (bsmArg instanceof Handle) {
            Handle handle = (Handle) bsmArg;
            String oldName = handle.getName();
            String hOwner = handle.getOwner();
            String newName = this.lookup.getRemappedMethodName(hOwner, oldName, handle.getDesc());
            String newOwner = this.lookup.getRemappedClassNameFast(hOwner);
            boolean modified = oldName != newName;
            if (newOwner != null) {
                hOwner = newOwner;
                modified = true;
            }
            String desc = handle.getDesc();
            sharedStringBuilder.setLength(0);
            if (Remapper.remapSignature(this.lookup, desc, sharedStringBuilder)) {
                desc = sharedStringBuilder.toString();
                modified = true;
            }
            if (modified) {
                bsmArgs[index] = new Handle(handle.getTag(), hOwner, newName, desc, handle.isInterface());
            }
        } else if (bsmArg instanceof String) {
            // Do nothing. I'm kind of surprised that I built this method modular enough that this was a straightforward fix
        } else {
            throw new IllegalArgumentException("Unexpected bsm arg class at index " + index + " for " + Arrays.toString(bsmArgs) + ". Class is " + bsmArg.getClass().getName());
        }
    }

    private void remapFrameNode(@NotNull FrameNode frameNode, @NotNull StringBuilder sharedStringBuilder) {
        if (frameNode.stack != null) {
            int i = frameNode.stack.size();
            while (i-- != 0) {
                Object o = frameNode.stack.get(i);
                if (o instanceof String) {
                    frameNode.stack.set(i, Remapper.remapInternalName(this.lookup, (String) o, sharedStringBuilder));
                }
            }
        }
        if (frameNode.local != null) {
            int i = frameNode.local.size();
            while (i-- != 0) {
                Object o = frameNode.local.get(i);
                if (o instanceof String) {
                    frameNode.local.set(i, Remapper.remapInternalName(this.lookup, (String) o, sharedStringBuilder));
                }
            }
        }
    }

    private void remapModule(@NotNull ModuleNode module, @NotNull StringBuilder sharedStringBuilder) {
        // This is really stupid design
        if (module.mainClass != null) {
            module.mainClass = this.lookup.getRemappedClassName(module.mainClass);
        }
        if (module.uses != null) {
            int i = module.uses.size();
            while (i-- != 0) {
                module.uses.set(i, Remapper.remapInternalName(this.lookup, module.uses.get(i), sharedStringBuilder));
            }
        }
    }

    /**
     * Remap a {@link ClassNode}, modifying it and it's contents.
     *
     * <p>Warning: The given {@link StringBuilder} instance may be overwritten.
     * The contents of the {@link StringBuilder} before the method call are completely irrelevant,
     * and the contents after the call may be garbage. The reason {@link Remapper} allows
     * supplying {@link StringBuilder} instances is mostly for performance reasons in order
     * to cut down the amount of times a {@link StringBuilder} instance is allocated only
     * to be instantly discarded again.
     *
     * @param node The node to remap
     * @param sharedBuilder The {@link StringBuilder} instance to use for string manipulation.
     * @return The current {@link Remapper} instance, for chaining
     */
    @Contract(pure = false, mutates = "param1,param2", value = "_, _ -> this")
    @NotNull
    public Remapper remapNode(@NotNull ClassNode node, @NotNull StringBuilder sharedBuilder) {
        for (FieldNode field : node.fields) {
            this.remapNode(node.name, field, sharedBuilder);
        }

        for (InnerClassNode innerClass : node.innerClasses) {
            // TODO: Should we also remap the inner names?
            String outerName = innerClass.outerName;
            if (outerName != null) {
                innerClass.outerName = this.lookup.getRemappedClassName(outerName);
            }
            innerClass.name = this.lookup.getRemappedClassName(innerClass.name);
        }

        {
            int i = node.interfaces.size();
            while (i-- != 0) {
                node.interfaces.set(i, this.lookup.getRemappedClassName(node.interfaces.get(i)));
            }
        }

        this.remapAnnotations(node.invisibleTypeAnnotations, sharedBuilder);
        this.remapAnnotations(node.invisibleAnnotations, sharedBuilder);
        this.remapAnnotations(node.visibleTypeAnnotations, sharedBuilder);
        this.remapAnnotations(node.visibleAnnotations, sharedBuilder);

        for (MethodNode method : node.methods) {
            this.remapNode(node.name, method, sharedBuilder);
        }

        ModuleNode module = node.module;
        if (module != null) {
            this.remapModule(module, sharedBuilder);
        }

        if (node.nestHostClass != null) {
            // Note: This was formerly remapInternalName, however I assume that we can get by with below logic
            node.nestHostClass = this.lookup.getRemappedClassName(node.nestHostClass);
        }

        if (node.nestMembers != null) {
            int i = node.nestMembers.size();
            while (i-- != 0) {
                // Similarly this also used to be remapInternalName, but I believe we can get by optimising things a little bit more
                node.nestMembers.set(i, this.lookup.getRemappedClassName(node.nestMembers.get(i)));
            }
        }

        if (node.outerClass != null) {
            if (node.outerMethod != null && node.outerMethodDesc != null) {
                node.outerMethod = this.lookup.getRemappedMethodName(node.outerClass, node.outerMethod, node.outerMethodDesc);
            }
            node.outerClass = this.lookup.getRemappedClassName(node.outerClass);
        }

        if (node.outerMethodDesc != null) {
            sharedBuilder.setLength(0);
            if (Remapper.remapSignature(this.lookup, node.outerMethodDesc, sharedBuilder)) {
                node.outerMethodDesc = sharedBuilder.toString();
            }
        }

        if (node.permittedSubclasses != null) {
            int i = node.permittedSubclasses.size();
            while (i-- != 0) {
                node.permittedSubclasses.set(i, this.lookup.getRemappedClassName(node.permittedSubclasses.get(i)));
            }
        }

        if (node.recordComponents != null) {
            // This requires eventual testing as I do not make use of codebases with Java9+ features.
            for (RecordComponentNode record : node.recordComponents) {
                sharedBuilder.setLength(0);
                if (Remapper.remapSignature(this.lookup, record.descriptor, sharedBuilder)) {
                    record.descriptor = sharedBuilder.toString();
                }
                this.remapAnnotations(record.invisibleAnnotations, sharedBuilder);
                this.remapAnnotations(record.invisibleTypeAnnotations, sharedBuilder);
                this.remapAnnotations(record.visibleAnnotations, sharedBuilder);
                this.remapAnnotations(record.visibleTypeAnnotations, sharedBuilder);
                if (record.signature != null) {
                    sharedBuilder.setLength(0);
                    if (Remapper.remapSignature(this.lookup, record.signature, sharedBuilder)) { // FIXME Especially that one looks debatable - do record signatures really follow "normal" signature behaviour
                        record.signature = sharedBuilder.toString();
                    }
                }
            }
        }

        if (node.signature != null) {
            sharedBuilder.setLength(0);
            // Class signatures are formatted differently than method or field signatures, but we can just ignore this
            // caveat here as the method will consider the invalid tokens are primitive objects. (sometimes laziness pays off)
            if (Remapper.remapSignature(this.lookup, node.signature, sharedBuilder)) {
                node.signature = sharedBuilder.toString();
            }
        }

        if (!Objects.isNull(node.superName)) {
            node.superName = this.lookup.getRemappedClassName(node.superName);
        }

        node.name = this.lookup.getRemappedClassName(node.name);
        return this;
    }

    /**
     * Remap a {@link FieldNode}, modifying it and it's contents.
     *
     * <p>Warning: The given {@link StringBuilder} instance may be overwritten.
     * The contents of the {@link StringBuilder} before the method call are completely irrelevant,
     * and the contents after the call may be garbage. The reason {@link Remapper} allows
     * supplying {@link StringBuilder} instances is mostly for performance reasons in order
     * to cut down the amount of times a {@link StringBuilder} instance is allocated only
     * to be instantly discarded again.
     *
     * @param owner The owner of the member (i.e. the class where it is defined), represented as an {@link Type#getInternalName() internal name}
     * @param field The node to remap
     * @param sharedStringBuilder The {@link StringBuilder} instance to use for string manipulation.
     * @return The current {@link Remapper} instance, for chaining
     */
    @NotNull
    @Contract(pure = false, mutates = "param2, param3", value = "_, _, _ -> this")
    public Remapper remapNode(@NotNull String owner, @NotNull FieldNode field, @NotNull StringBuilder sharedStringBuilder) {
        field.name = this.lookup.getRemappedFieldName(owner, field.name, field.desc);

        int typeType = field.desc.charAt(0);
        if (typeType == '[' || typeType == 'L') {
            // Remap descriptor
            sharedStringBuilder.setLength(0);
            field.desc = Remapper.remapSingleDesc(this.lookup, field.desc, sharedStringBuilder);
            // Remap signature
            if (field.signature != null) {
                sharedStringBuilder.setLength(0);
                if (Remapper.remapSignature(this.lookup, field.signature, sharedStringBuilder)) {
                    field.signature = sharedStringBuilder.toString();
                }
            }
        }

        this.remapAnnotations(field.invisibleTypeAnnotations, sharedStringBuilder);
        this.remapAnnotations(field.invisibleAnnotations, sharedStringBuilder);
        this.remapAnnotations(field.visibleAnnotations, sharedStringBuilder);
        this.remapAnnotations(field.visibleTypeAnnotations, sharedStringBuilder);

        return this;
    }

    /**
     * Remap a {@link MethodNode}, modifying it and it's contents.
     *
     * <p>Warning: The given {@link StringBuilder} instance may be overwritten.
     * The contents of the {@link StringBuilder} before the method call are completely irrelevant,
     * and the contents after the call may be garbage. The reason {@link Remapper} allows
     * supplying {@link StringBuilder} instances is mostly for performance reasons in order
     * to cut down the amount of times a {@link StringBuilder} instance is allocated only
     * to be instantly discarded again.
     *
     * @param owner The owner of the member (i.e. the class where it is defined), represented as an {@link Type#getInternalName() internal name}
     * @param method The node to remap
     * @param sharedStringBuilder The {@link StringBuilder} instance to use for string manipulation.
     * @return The current {@link Remapper} instance, for chaining
     */
    @NotNull
    @Contract(pure = false, mutates = "param2, param3", value = "_, _, _ -> this")
    public Remapper remapNode(@NotNull String owner, @NotNull MethodNode method, @NotNull StringBuilder sharedStringBuilder) {
        method.name = this.lookup.getRemappedMethodName(owner, method.name, method.desc);
        {
            int i = method.exceptions.size();
            while (i-- != 0) {
                method.exceptions.set(i, this.lookup.getRemappedClassName(method.exceptions.get(i)));
            }
        }
        this.remapAnnotations(method.invisibleTypeAnnotations, sharedStringBuilder);
        this.remapAnnotations(method.invisibleLocalVariableAnnotations, sharedStringBuilder);
        this.remapAnnotations(method.invisibleAnnotations, sharedStringBuilder);
        this.remapAnnotations(method.visibleAnnotations, sharedStringBuilder);
        this.remapAnnotations(method.visibleTypeAnnotations, sharedStringBuilder);
        this.remapAnnotations(method.visibleLocalVariableAnnotations, sharedStringBuilder);
        if (method.invisibleParameterAnnotations != null) {
            for (List<AnnotationNode> annotations : method.invisibleParameterAnnotations) {
                this.remapAnnotations(annotations, sharedStringBuilder);
            }
        }
        if (method.visibleParameterAnnotations != null) {
            for (List<AnnotationNode> annotations : method.visibleParameterAnnotations) {
                this.remapAnnotations(annotations, sharedStringBuilder);
            }
        }
        if (method.localVariables != null) {
            for (LocalVariableNode lvn : method.localVariables) {
                lvn.desc = Remapper.remapSingleDesc(this.lookup, lvn.desc, sharedStringBuilder);
                String signature = lvn.signature;
                if (signature != null) {
                    sharedStringBuilder.setLength(0);
                    if (Remapper.remapSignature(this.lookup, signature, sharedStringBuilder)) {
                        lvn.signature = sharedStringBuilder.toString();
                    }
                }
            }
        }
        for (TryCatchBlockNode catchBlock : method.tryCatchBlocks) {
            if (catchBlock.type != null) {
                catchBlock.type = this.lookup.getRemappedClassName(catchBlock.type);
            }
            this.remapAnnotations(catchBlock.visibleTypeAnnotations, sharedStringBuilder);
            this.remapAnnotations(catchBlock.invisibleTypeAnnotations, sharedStringBuilder);
        }
        sharedStringBuilder.setLength(0);
        if (Remapper.remapSignature(this.lookup, method.desc, sharedStringBuilder)) {
            // The field signature and method desc system are similar enough that this works;
            method.desc = sharedStringBuilder.toString();
        }
        if (method.signature != null) {
            sharedStringBuilder.setLength(0);
            if (Remapper.remapSignature(this.lookup, method.signature, sharedStringBuilder)) {
                // Method signature and field signature are also similar enough
                method.signature = sharedStringBuilder.toString();
            }
        }
        if (method.annotationDefault != null && !(method.annotationDefault instanceof Number)) {
            // Little cheat to avoid writing the same code twice :)
            List<Object> annotationList = Arrays.asList(method.annotationDefault);
            this.remapAnnotationValue(method.annotationDefault, 0, annotationList, sharedStringBuilder);
            method.annotationDefault = annotationList.get(0);
        }
        InsnList instructions = method.instructions;
        if (instructions.size() != 0) {
            AbstractInsnNode insn = instructions.getFirst();
            while (insn != null) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode instruction = (FieldInsnNode) insn;
                    instruction.name = this.lookup.getRemappedFieldName(instruction.owner, instruction.name, instruction.desc);
                    instruction.desc = Remapper.remapSingleDesc(this.lookup, instruction.desc, sharedStringBuilder);
                    instruction.owner = this.lookup.getRemappedClassName(instruction.owner);
                } else if (insn instanceof FrameNode) {
                    this.remapFrameNode((FrameNode) insn, sharedStringBuilder);
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode specialisedInsn = (InvokeDynamicInsnNode) insn;
                    String lambdaType = specialisedInsn.desc.substring(specialisedInsn.desc.indexOf(')') + 2, specialisedInsn.desc.length() - 1);
                    Object[] bsmArgs = specialisedInsn.bsmArgs;

                    specialisedInsn.name = this.lookup.getRemappedMethodName(lambdaType, specialisedInsn.name, ((Type) bsmArgs[0]).getDescriptor());

                    int i = bsmArgs.length;
                    while (i-- != 0) {
                        this.remapBSMArg(bsmArgs, i, sharedStringBuilder);
                    }

                    sharedStringBuilder.setLength(0);
                    if (Remapper.remapSignature(this.lookup, specialisedInsn.desc, sharedStringBuilder)) {
                        specialisedInsn.desc = sharedStringBuilder.toString();
                    }
                } else if (insn instanceof LdcInsnNode) {
                    LdcInsnNode specialisedInsn = (LdcInsnNode) insn;
                    if (specialisedInsn.cst instanceof Type) {
                        String descString = ((Type) specialisedInsn.cst).getDescriptor();
                        String newDescString = Remapper.remapSingleDesc(this.lookup, descString, sharedStringBuilder);
                        if (descString != newDescString) {
                            specialisedInsn.cst = Type.getType(newDescString);
                        }
                    }
                } else if (insn instanceof MethodInsnNode) {
                    MethodInsnNode instruction = (MethodInsnNode) insn;
                    boolean isArray = instruction.owner.codePointAt(0) == '[';
                    if (!isArray) { // Methods such as .hashCode or .equals can be used with arrays, too
                        instruction.name = this.lookup.getRemappedMethodName(instruction.owner, instruction.name, instruction.desc);
                        instruction.owner = this.lookup.getRemappedClassName(instruction.owner);
                    } else {
                        sharedStringBuilder.setLength(0);
                        instruction.owner = Remapper.remapSingleDesc(this.lookup, instruction.owner, sharedStringBuilder);
                    }
                    sharedStringBuilder.setLength(0);
                    if (Remapper.remapSignature(this.lookup, instruction.desc, sharedStringBuilder)) {
                        instruction.desc = sharedStringBuilder.toString();
                    }
                } else if (insn instanceof MultiANewArrayInsnNode) {
                    MultiANewArrayInsnNode instruction = (MultiANewArrayInsnNode) insn;
                    instruction.desc = Remapper.remapSingleDesc(this.lookup, instruction.desc, sharedStringBuilder);
                } else if (insn instanceof TypeInsnNode) {
                    TypeInsnNode instruction = (TypeInsnNode) insn;
                    instruction.desc = Remapper.remapInternalName(this.lookup, instruction.desc, sharedStringBuilder);
                }
                insn = insn.getNext();
            }
        }

        return this;
    }
}
