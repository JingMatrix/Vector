// No source/target override: the root build sets both to 21 for every Java project, and this one
// is `compileOnly` everywhere it is used, so nothing here ever reaches a device. Pinning it to 8
// only earned three "source value 8 is obsolete" warnings on every build.
plugins { `java-library` }
