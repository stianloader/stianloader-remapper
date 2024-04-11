# Stianloader-remapper

Stianloader-remapper is an in-memory remapping engine for java bytecode. It uses objectweb's
asm-tree library to operate directly on ClassNodes, resulting in no overhead for pipelines
that already make use of them.

The main alternative to Stianloader-remapper is fabricmc's TinyRemapper, which uses the objectweb
asm library and operates using ClassVisitors. However, it's main drawback is that it does not
make use of in-memory remapping, meaning that it has to read from a file and write back to a file.
If one chooses to apply layered mappings in a quick and dirty way by plainly remapping the contents
repeatedly, then a severe performance loss can be expected due to I/O overhead caused by reading
and writing to the same file.

## Remapping Mixins

TinyRemapper's strength is in being able to remap Mixins without the use of annotation processors
(AP). Stianloader-remapper itself does not provide such functionality but this functionality will
be provided as an add-on library in the future.
