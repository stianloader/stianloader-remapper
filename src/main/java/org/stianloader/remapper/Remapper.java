package org.stianloader.remapper;

import java.util.Arrays;
import java.util.List;

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
     * Remaps a field descriptor.
     *
     * @param fieldDesc The old (unmapped) field descriptor
     * @param sharedBuilder A shared cached string builder. The contents of the string builder are wiped and after the invocation the contents are undefined
     * @return The new (remapped) field descriptor. It <b>can</b> be identity identical to the "fieldDesc" if it didn't need to be altered
     */
    @SuppressWarnings("null")
    @NotNull
    public String getRemappedFieldDescriptor(@NotNull String fieldDesc, @NotNull StringBuilder sharedBuilder) {
        sharedBuilder.setLength(0);
        return this.remapSingleDesc(fieldDesc, sharedBuilder);
    }

    /**
     * Remaps a method descriptor.
     *
     * @param methodDesc The old (unmapped) method descriptor
     * @param sharedBuilder A shared cached string builder. The contents of the string builder are wiped and after the invocation the contents are undefined
     * @return The new (remapped) method descriptor. It <b>can</b> be identity identical to the "methodDesc" if it didn't need to be altered
     */
    @NotNull
    public String getRemappedMethodDescriptor(@NotNull String methodDesc, @NotNull StringBuilder sharedBuilder) {
        sharedBuilder.setLength(0);
        if (!this.remapSignature(methodDesc, sharedBuilder)) {
            return methodDesc;
        }
        return sharedBuilder.toString();
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
            if (this.remapSignature(type, sharedStringBuilder)) {
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
                if (remapSignature(type.getDescriptor(), sharedStringBuilder)) {
                    bsmArgs[index] = Type.getMethodType(sharedStringBuilder.toString());
                }
            } else if (type.getSort() == Type.OBJECT) {
                String oldVal = type.getInternalName();
                String remappedVal = remapInternalName(oldVal, sharedStringBuilder);
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
            if (this.remapSignature(desc, sharedStringBuilder)) {
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

    private void remapFrameNode(FrameNode frameNode, StringBuilder sharedStringBuilder) {
        if (frameNode.stack != null) {
            int i = frameNode.stack.size();
            while (i-- != 0) {
                Object o = frameNode.stack.get(i);
                if (o instanceof String) {
                    frameNode.stack.set(i, this.remapInternalName((String) o, sharedStringBuilder));
                }
            }
        }
        if (frameNode.local != null) {
            int i = frameNode.local.size();
            while (i-- != 0) {
                Object o = frameNode.local.get(i);
                if (o instanceof String) {
                    frameNode.stack.set(i, this.remapInternalName((String) o, sharedStringBuilder));
                }
            }
        }
    }

    private String remapInternalName(String internalName, StringBuilder sharedStringBuilder) {
        if (internalName.codePointAt(0) == '[') {
            return this.remapSingleDesc(internalName, sharedStringBuilder);
        } else {
            return this.lookup.getRemappedClassName(internalName);
        }
    }

    private void remapModule(ModuleNode module, StringBuilder sharedStringBuilder) {
        // This is really stupid design
        if (module.mainClass != null) {
            module.mainClass = this.lookup.getRemappedClassName(module.mainClass);
        }
        if (module.uses != null) {
            int i = module.uses.size();
            while (i-- != 0) {
                module.uses.set(i, this.remapInternalName(module.uses.get(i), sharedStringBuilder));
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
            if (this.remapSignature(node.outerMethodDesc, sharedBuilder)) {
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
                if (this.remapSignature(record.descriptor, sharedBuilder)) {
                    record.descriptor = sharedBuilder.toString();
                }
                this.remapAnnotations(record.invisibleAnnotations, sharedBuilder);
                this.remapAnnotations(record.invisibleTypeAnnotations, sharedBuilder);
                this.remapAnnotations(record.visibleAnnotations, sharedBuilder);
                this.remapAnnotations(record.visibleTypeAnnotations, sharedBuilder);
                if (record.signature != null) {
                    sharedBuilder.setLength(0);
                    if (this.remapSignature(record.signature, sharedBuilder)) { // FIXME Especially that one looks debatable - do record signatures really follow "normal" signature behaviour
                        record.signature = sharedBuilder.toString();
                    }
                }
            }
        }

        if (node.signature != null) {
            sharedBuilder.setLength(0);
            // Class signatures are formatted differently than method or field signatures, but we can just ignore this
            // caveat here as the method will consider the invalid tokens are primitive objects. (sometimes laziness pays off)
            if (this.remapSignature(node.signature, sharedBuilder)) {
                node.signature = sharedBuilder.toString();
            }
        }

        if (node.superName != null) {
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
            field.desc = this.remapSingleDesc(field.desc, sharedStringBuilder);
            // Remap signature
            if (field.signature != null) {
                sharedStringBuilder.setLength(0);
                if (this.remapSignature(field.signature, sharedStringBuilder)) {
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
                int typeType = lvn.desc.charAt(0);
                boolean isObjectArray = typeType == '[';
                int arrayDimension = 0;
                if (isObjectArray) {
                    if (lvn.desc.codePointBefore(lvn.desc.length()) == ';') {
                        // calculate depth
                        int arrayType;
                        do {
                            arrayType = lvn.desc.charAt(++arrayDimension);
                        } while (arrayType == '[');
                    } else {
                        isObjectArray = false;
                    }
                }
                if (isObjectArray || typeType == 'L') {
                    // Remap descriptor
                    Type type = Type.getType(lvn.desc);
                    String internalName = type.getInternalName();
                    String newInternalName = this.lookup.getRemappedClassNameFast(internalName);
                    if (newInternalName != null) {
                        if (isObjectArray) {
                            sharedStringBuilder.setLength(arrayDimension);
                            for (int i = 0; i < arrayDimension; i++) {
                                sharedStringBuilder.setCharAt(i, '[');
                            }
                            sharedStringBuilder.append(newInternalName);
                            sharedStringBuilder.append(';');
                            lvn.desc = sharedStringBuilder.toString();
                        } else {
                            lvn.desc = 'L' + newInternalName + ';';
                        }
                    }
                    if (lvn.signature != null) {
                        sharedStringBuilder.setLength(0);
                        if (this.remapSignature(lvn.signature, sharedStringBuilder)) {
                            lvn.signature = sharedStringBuilder.toString();
                        }
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
        if (this.remapSignature(method.desc, sharedStringBuilder)) {
            // The field signature and method desc system are similar enough that this works;
            method.desc = sharedStringBuilder.toString();
        }
        if (method.signature != null) {
            sharedStringBuilder.setLength(0);
            if (this.remapSignature(method.signature, sharedStringBuilder)) {
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
        if (instructions != null && instructions.size() != 0) {
            AbstractInsnNode insn = instructions.getFirst();
            while (insn != null) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode instruction = (FieldInsnNode) insn;
                    instruction.name = this.lookup.getRemappedFieldName(instruction.owner, instruction.name, instruction.desc);
                    instruction.desc = this.remapSingleDesc(instruction.desc, sharedStringBuilder);
                    instruction.owner = this.lookup.getRemappedClassName(instruction.owner);
                } else if (insn instanceof FrameNode) {
                    this.remapFrameNode((FrameNode) insn, sharedStringBuilder);
                } else if (insn instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode specialisedInsn = (InvokeDynamicInsnNode) insn;
                    Object[] bsmArgs = specialisedInsn.bsmArgs;
                    int i = bsmArgs.length;
                    while (i-- != 0) {
                        this.remapBSMArg(bsmArgs, i, sharedStringBuilder);
                    }
                    sharedStringBuilder.setLength(0);
                    if (this.remapSignature(specialisedInsn.desc, sharedStringBuilder)) {
                        specialisedInsn.desc = sharedStringBuilder.toString();
                    }
                } else if (insn instanceof LdcInsnNode) {
                    LdcInsnNode specialisedInsn = (LdcInsnNode) insn;
                    if (specialisedInsn.cst instanceof Type) {
                        String descString = ((Type) specialisedInsn.cst).getDescriptor();
                        String newDescString = this.remapSingleDesc(descString, sharedStringBuilder);
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
                        instruction.owner = this.remapSingleDesc(instruction.owner, sharedStringBuilder);
                    }
                    sharedStringBuilder.setLength(0);
                    if (this.remapSignature(instruction.desc, sharedStringBuilder)) {
                        instruction.desc = sharedStringBuilder.toString();
                    }
                } else if (insn instanceof MultiANewArrayInsnNode) {
                    MultiANewArrayInsnNode instruction = (MultiANewArrayInsnNode) insn;
                    instruction.desc = this.remapSingleDesc(instruction.desc, sharedStringBuilder);
                } else if (insn instanceof TypeInsnNode) {
                    TypeInsnNode instruction = (TypeInsnNode) insn;
                    instruction.desc = this.remapInternalName(instruction.desc, sharedStringBuilder);
                }
                insn = insn.getNext();
            }
        }

        return this;
    }

    private boolean remapSignature(String signature, StringBuilder out) {
        return this.remapSignature(out, signature, 0, signature.length());
    }

    private boolean remapSignature(StringBuilder signatureOut, String signature, int start, int end) {
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
                    String newName = this.lookup.getRemappedClassNameFast(name);
                    boolean modified = false;
                    if (newName != null) {
                        name = newName;
                        modified = true;
                    }
                    signatureOut.appendCodePoint(type);
                    signatureOut.append(name);
                    signatureOut.append(';');
                    modified |= remapSignature(signatureOut, signature, ++endObject, end);
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
                    String newName = this.lookup.getRemappedClassNameFast(name);
                    boolean modified = false;
                    if (newName != null) {
                        name = newName;
                        modified = true;
                    }
                    signatureOut.append('L');
                    signatureOut.append(name);
                    signatureOut.append('<');
                    modified |= remapSignature(signatureOut, signature, endObject + 1, endGenerics++);
                    signatureOut.append('>');
                    // apparently that can be rarely be a '.', don't ask when or why exactly this occurs
                    signatureOut.appendCodePoint(signature.codePointAt(endGenerics));
                    modified |= remapSignature(signatureOut, signature, ++endGenerics, end);
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
            return remapSignature(signatureOut, signature, start, end); // Did not modify the signature - but following operations could
        }
    }

    private String remapSingleDesc(String input, StringBuilder sharedBuilder) {
        int indexofL = input.indexOf('L');
        if (indexofL == -1) {
            return input;
        }
        int length = input.length();
        String internalName = input.substring(indexofL + 1, length - 1);
        String newInternalName = this.lookup.getRemappedClassNameFast(internalName);
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
}
