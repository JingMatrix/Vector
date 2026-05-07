val androidSourceCompatibility: JavaVersion by rootProject.extra
val androidTargetCompatibility: JavaVersion by rootProject.extra

plugins {
    id("java-library")
}

java {
    sourceCompatibility = androidSourceCompatibility
    targetCompatibility = androidTargetCompatibility
    sourceSets {
        main {
            java.srcDirs("commons-lang/src/main/java", "local")
        }
    }
}

val lang3Src = "commons-lang/src/main/java/org/apache/commons/lang3"
val localDir = "local/generated"

tasks.register<Copy>("ClassUtilsX") {
    from("$lang3Src/ClassUtils.java")
    into(localDir)
    filter { line: String -> line.replace("ClassUtils", "ClassUtilsX") }
    rename("(.+).java", "$1X.java")
}

tasks.register<Copy>("SerializationUtilsX") {
    from("$lang3Src/SerializationUtils.java")
    into(localDir)
    filter { line: String -> line.replace("SerializationUtils", "SerializationUtilsX") }
    rename("(.+).java", "$1X.java")
}

tasks.compileJava {
    dependsOn("ClassUtilsX")
    dependsOn("SerializationUtilsX")
}
