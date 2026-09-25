**Status: Adopted.** Reflected in [building blocks](../arc42-005-building-blocks/README.md).

# The engine takes no framework, which is why it can be hosted in any

The question arrived with the Spring Boot wrappers. The store now ships
modules that depend on Spring, an application adds one dependency and serves
tenants, and the obvious reading is that R2 — *no heavyweight application
framework* — has quietly stopped being true.

**Direction: R2 is unchanged, and the wrappers are the reason it matters.**
It already says *in the engine*, and that phrase now does work it did not
have to do before. The engine names no framework. What was added is glue,
outside it, that an adopter may take or leave.

## The argument runs the other way round from how it reads

The tempting sentence is "the store supports Spring Boot". The true one is
that the store chose nothing, and support fell out of that.

A store that had picked a framework internally — its lifecycle for startup,
its context for wiring, its configuration binding for a tenant declaration —
could not have been glued to a second one at all. Not *with difficulty*: a
second binding would have to reimplement the first one's decisions, and would
inherit its version, its context lifecycle and its notion of when a bean
exists. That is a port, and ports are how a project acquires two of
everything.

Because the engine picked nothing, a binding is a module. `assembly/` holds
three, each some hundreds of lines, and each one's whole job is to say what a
bean means to a store that has never heard of beans. A Micronaut assembly
would be a fourth module beside them, written against the same host, and
nothing in the engine would learn about it.

## What was measured, in the sense that anything here is

`assembly/spring-boot-core` was written as an assembly, named for Spring,
sitting in the band reserved for framework bindings — and it turned out to
import nothing from Spring. Not one class. It also declared
`spring-boot-autoconfigure` and never used it.

That is the load-bearing observation, because it was not arranged. The shared
host — boot a framework, install a bundle set, compute the package list, hand
back a lookup — is framework-free *in fact*, having been written by somebody
building a Spring binding and given a Spring name. It is
[`core/dbo-embedded`](https://github.com/jengu-net/dbo/blob/main/core/dbo-embedded/README.md) now, and it is the
edge of the framework-free layer rather than the floor of a Spring one.

**And it produced the rule**: a module belongs to `assembly/` by what it
imports, not by who calls it. One that names no framework is `core/` even
when a single assembly is its only caller today — otherwise the first binding
to arrive quietly claims the shared host, and the second inherits a dependency
on the first one's framework, which is the port this whole arrangement avoids.

## The consequence that is not architectural

Spring is what most developers arriving here already know, so it is the first
assembly and the guide's spine: an application that serves the sample world
because it added one dependency is a shorter first hour than a launcher and a
bundle list.

**The framework-free path is not the lesser one for being second in the
guide.** It is the layer both assemblies are built on — `core/dbo-embedded`,
used directly — and every promise the store makes is kept there without a
framework anywhere in the process. A reader who wants no framework is reading
a real path, and the guide calls it advanced mode because it asks more of the
reader, not because it asks more of the store.

## What this does not decide

No second assembly is planned. Micronaut is named here as the shape a second
would take — a module, not a port — and naming a shape is not a commitment to
build it.

Nor does it make an assembly's API the store's. What `assembly/` publishes is
a binding's vocabulary: an annotation that means *this bean is a step*, a
property that means *this is the lane*. The store's own surface is the door,
and it is reachable with no assembly in the process at all.
