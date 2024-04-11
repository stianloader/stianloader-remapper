module org.stianloader.remapper {
    requires transitive org.objectweb.asm.tree;
    requires transitive java.base;
    requires org.jetbrains.annotations;

    exports org.stianloader.remapper;
}
