import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * Executes given maven command
 * @param mvnCommand String containing the command
 * @return stdout of the execution as list lines
 */
private Stream<String> mvnExecute(String mvnCommand) {
    def out = new StringBuilder(), err = new StringBuilder()
    log.info("Resolving Maven reactor projects for command '$mvnCommand'")
    log.info("Waiting up to $mavenExecutionTimeoutMs ms for resolution to end.")
    def stdoutLogFile = "mvn-std-out.log"
    log.info("Standard output will be available in ${Path.of(logdir).resolve(stdoutLogFile).toString()} after the build finishes.")
    def mvnExec = mvnCommand
            .execute(null, new File(basedir)
                    .listFiles({ d, f -> d.isDirectory() } as FilenameFilter)[0])
    mvnExec.consumeProcessOutput(out, err)
    mvnExec.waitForOrKill(Long.valueOf(mavenExecutionTimeoutMs))
    logDebug(stdoutLogFile, out.toString().lines().collect(Collectors.toList()))
    if (mvnExec.exitValue() != 0) {
        def stderrLogFile = "mvn-std-err.log"
        log.error("Maven build unsuccessful with code ${mvnExec.exitValue()}, " +
                "see ${Path.of(logdir).resolve(stderrLogFile).toString()}.")
        logDebug(stderrLogFile, err.toString().lines().collect(Collectors.toList()))
        throw new RuntimeException("Mvn command ended with error code ${mvnExec.exitValue()}")
    }
    return out.toString().lines()
}

/**
 * Trim path prefix up to given basedir.
 * @param value absolute path to be trimmed
 * @param prefix absolute path to be used as prefix
 * @return relative path between the two
 */
private String trimPrefix(Path value, Path prefix) {
    return prefix.relativize(value).toString();
}

/**
 * Return a list of string paths to pom project directories that are supposed to be leaf ones - no sub-modules.
 * @param projects string paths for all the projects within reactor
 * @return list of strings for paths that don't have any children in the list
 */
private List<String> resolveLeafProjects(Stream<String> projects) {
    List<String> lines = projects.map({ it -> trimPrefix(Path.of(it), new File(basedir).toPath()) }).collect(Collectors.toList()).sort()
    List<String> leafProjects = new ArrayList<>()
    def logFile = "leaf-projects.log"
    log.info("Starting leaf projects resolution, results will be in ${Path.of(logdir).resolve(logFile).toString()}")
    for (int i = 0; i < lines.size(); i++) {
        String current = lines[i]
        if (lines.size() > i + 1) {
            String next = lines[i + 1]
            if (!Path.of(next).startsWith(current)) {
                log.debug("Adding $current as a leaf project, it has no child")
                leafProjects.add(current)
            } else {
                log.debug("Skipping '$current' project, cause it has child '$next'")
            }
        } else {
            log.debug("Adding $current as a leaf project, cause no other project is in reactor.")
            leafProjects.add(current)
        }
    }
    logDebug(logFile, leafProjects)
    return leafProjects
}

/**
 * Log into given file contents of the provided list of paths.
 * @param fileName target dir to be created within logdir (passed from config)
 * @param projectList list of paths
 */
private void logDebug(String fileName, ArrayList<String> projectList) {
    new File(logdir + "/" + fileName).write projectList.stream().map({ it ->
        it + System.lineSeparator()
    }).collect(Collectors.joining())
}

/**
 * Recursively find all folders that contain pom.xml
 * @return list of string paths
 */
private List<String> findAllDirsWithPom() {
    File base = new File(basedir)
    List<File> poms = new ArrayList<>()
    base.traverse(type: groovy.io.FileType.FILES, nameFilter: "pom.xml") { it -> poms.add(it) }
    return poms.stream().map({pom -> trimPrefix(pom.toPath().getParent().toAbsolutePath(), base.toPath())}).collect(Collectors.toList())
}

/**
 * Write a invokerScriptName named file into given locations. Write contents return value.
 * @param locations list of string paths
 * @param value value to be written (false/true)
 */
private void writeInvokerScripts(List<String> locations, String value) {
    locations.forEach({ location ->
        def filePath = Path.of(basedir).resolve(location).resolve(invokerScriptName).toString()
        new File(filePath).write "return $value"
    })
}

/**
 * Get Maven command string including possible arguments passed from configuration.x`
 * Tailor the mvn command used to inherit settings of the invoking maven build.
 * @return
 */
private String getMvnCommand() {
    String mvnCommandToRun = "mvn -q -am exec:exec -Dexec.executable=pwd"
    if (mavenReactorFiltering) {
        mvnCommandToRun = mvnCommandToRun + " $mavenReactorFiltering"
    }
    if (mavenRepoLocal && Files.exists(Path.of(mavenRepoLocal))) {
        mvnCommandToRun = mvnCommandToRun + " -Dmaven.repo.local=$mavenRepoLocal"
    }
    if (mavenSettings && Files.exists(Path.of(mavenSettings))) {
        mvnCommandToRun = mvnCommandToRun + " -s $mavenSettings"
    }
    return mvnCommandToRun
}

/**
 * Method to prevent execution of provided projects by adding invoker-specific script into project folder.
 * @param toExclude list of projects to exclude
 */
private void excludeFromInvokerExecution(List toExclude) {
    def ingoredLog = "ignored-interim-poms.log"
    log.info("Ignored projects are logged as ${Path.of(logdir).resolve(ingoredLog).toString()} .")
    logDebug(ingoredLog, toExclude)
    writeInvokerScripts(toExclude, "false")
}

Stream<String> productizedPoms = mvnExecute(getMvnCommand())
List leafProjects = resolveLeafProjects(productizedPoms)
List dirsWithPoms = findAllDirsWithPom()
List toExclude = dirsWithPoms.minus(leafProjects)
excludeFromInvokerExecution(toExclude)
