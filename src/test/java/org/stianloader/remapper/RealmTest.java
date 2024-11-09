package org.stianloader.remapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.stianloader.remapper.SimpleTopLevelLookup.MemberRealm;

public class RealmTest {

    @Test
    public void testTransitiveRealmDiscovery() {
        List<@NotNull ClassNode> testClasses = new ArrayList<>();
        {
            ClassNode testClass = new ClassNode();
            testClass.name = "A";
            testClass.superName = "java/lang/Object";
            testClass.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "a", "()V", null, null));
            testClasses.add(testClass);
        }
        {
            ClassNode testClass = new ClassNode();
            testClass.name = "B";
            testClass.superName = "A";
            testClasses.add(testClass);
        }
        {
            ClassNode testClass = new ClassNode();
            testClass.name = "C";
            testClass.superName = "B";
            testClasses.add(testClass);
        }
        {
            ClassNode testClass = new ClassNode();
            testClass.name = "D";
            testClass.superName = "C";
            testClasses.add(testClass);
        }

        Map<@NotNull MemberRef, @NotNull MemberRealm> realms = SimpleTopLevelLookup.realmsOf(testClasses);

        assertEquals(4, realms.size());
        assertEquals(4, realms.get(new MemberRef("A","a", "()V")).realmMembers.size());
        assertEquals(4, realms.get(new MemberRef("B","a", "()V")).realmMembers.size());
        assertEquals(4, realms.get(new MemberRef("C","a", "()V")).realmMembers.size());
        assertEquals(4, realms.get(new MemberRef("D","a", "()V")).realmMembers.size());
    }
}
