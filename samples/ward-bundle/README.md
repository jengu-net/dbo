# The ward's bundle

An OSGi bundle of the clinic's own: it registers a `StepService` for
`hogwarts.ward.observe`, a step Hogwarts declares, and nothing else. It
compiles against the runner's step vocabulary and the OSGi core API, so it
resolves against the packages the clinic's framework shares from the system
bundle.

The clinic's application carries the jar as a resource and installs it into
the framework it owns
([`OwningTheFramework`](../spring-boot-server-app/src/main/java/cloud/jengu/dbo/samples/server/OwningTheFramework.java)),
which is where the store is installed too. The stories ask Hogwarts for the
step and read back the bundle's own id in what the run counted.

```
./gradlew :samples:ward-bundle:jar
```
