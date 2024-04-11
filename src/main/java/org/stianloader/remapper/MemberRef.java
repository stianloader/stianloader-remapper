package org.stianloader.remapper;

import java.util.HashMap;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Type;

/**
 * A reference to a class member (which can be a field or a method). Note that stianloader-remapper
 * does not mandate that the member exists and may refer to a member that exists in a superclass or even not at all.
 *
 * <p>For all intents and purposes this class is just a tuple of an owner, name and descriptor.
 *
 * <p>This class implements {@link #hashCode()} and {@link #equals(Object)} and can thus be used
 * in hashtable-based structures such as the key of an {@link HashMap}.
 *
 * <p>Instances of this class are immutable.
 */
public final class MemberRef {
    @NotNull
    private final String desc;
    @NotNull
    private final String name;
    @NotNull
    private final String owner;

    /**
     * Constructor.
     * All class names are represented using {@link Type#getInternalName() the internal name} of the class
     * - so forward slashes instead of dots.
     *
     * @param owner The owner of the member (not necessarily the class that defines the member)
     * @param name The name of the member
     * @param desc The descriptor of the member
     */
    public MemberRef(@NotNull String owner, @NotNull String name, @NotNull String desc) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
    }

    @Override
    @Contract(pure = true)
    public boolean equals(Object obj) {
        if (obj instanceof MemberRef) {
            MemberRef other = (MemberRef) obj;
            return other.owner.equals(this.owner)
                    && other.name.equals(this.name)
                    && other.desc.equals(this.desc);
        }
        return false;
    }

    /**
     * Obtains the descriptor of the member.
     *
     * <p>For methods, the first character of the descriptor will be '(', if the first
     * character is something else, it is a field.
     *
     * @return The descriptor of the member
     */
    @NotNull
    @Contract(pure = true)
    public String getDesc() {
        return this.desc;
    }

    /**
     * Obtains the name of the member.
     *
     * <p>This class does not define in which namespace the name is, it depends on the context in which
     * the {@link MemberRef} is being used in.
     *
     * @return The name of the member.
     */
    @NotNull
    @Contract(pure = true)
    public String getName() {
        return this.name;
    }

    /**
     * Obtains the owner of the member reference, represented via the {@link Type#getInternalName() the internal name}
     * of the class.
     *
     * @return The owner of the member reference
     */
    @NotNull
    @Contract(pure = true)
    public String getOwner() {
        return this.owner;
    }

    @Override
    @Contract(pure = true)
    public int hashCode() {
        return this.owner.hashCode() ^ this.name.hashCode() ^ this.desc.hashCode();
    }
}
