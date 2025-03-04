Project: /_project.yaml
Book: /_book.yaml

# Working with External Dependencies

{% include "_buttons.html" %}

Bazel can depend on targets from other projects. Dependencies from these other
projects are called _external dependencies_.

Note: Bazel 5.0 and newer has a new external dependency system, codenamed
"Bzlmod", which renders a lot of the content on this page obsolete. See [Bzlmod
user guide](/build/bzlmod) for more information.

The `WORKSPACE` file (or `WORKSPACE.bazel` file) in the
[workspace directory](/concepts/build-ref#workspace)
tells Bazel how to get other projects' sources. These other projects can
contain one or more `BUILD` files with their own targets. `BUILD` files within
the main project can depend on these external targets by using their name from
the `WORKSPACE` file.

For example, suppose there are two projects on a system:

```
/
  home/
    user/
      project1/
        WORKSPACE
        BUILD
        srcs/
          ...
      project2/
        WORKSPACE
        BUILD
        my-libs/
```

If `project1` wanted to depend on a target, `:foo`, defined in
`/home/user/project2/BUILD`, it could specify that a repository named
`project2` could be found at `/home/user/project2`. Then targets in
`/home/user/project1/BUILD` could depend on `@project2//:foo`.

The `WORKSPACE` file allows users to depend on targets from other parts of the
filesystem or downloaded from the internet. It uses the same syntax as `BUILD`
files, but allows a different set of rules called _repository rules_ (sometimes
also known as _workspace rules_). Bazel comes with a few [built-in repository
rules](/reference/be/workspace) and a set of [embedded Starlark repository
rules](/rules/lib/repo/index). Users can also write [custom repository
rules](/extending/repo) to get more complex behavior.

## Supported types of external dependencies {:#types}

A few basic types of external dependencies can be used:

- [Dependencies on other Bazel projects](#bazel-projects)
- [Dependencies on non-Bazel projects](#non-bazel-projects)
- [Dependencies on external packages](#external-packages)

### Depending on other Bazel projects {:#bazel-projects}

If you want to use targets from a second Bazel project, you can
use
[`local_repository`](/reference/be/workspace#local_repository),
[`git_repository`](/rules/lib/repo/git#git_repository)
or [`http_archive`](/rules/lib/repo/http#http_archive)
to symlink it from the local filesystem, reference a git repository or download
it (respectively).

For example, suppose you are working on a project, `my-project/`, and you want
to depend on targets from your coworker's project, `coworkers-project/`. Both
projects use Bazel, so you can add your coworker's project as an external
dependency and then use any targets your coworker has defined from your own
BUILD files. You would add the following to `my_project/WORKSPACE`:

```python
local_repository(
    name = "coworkers_project",
    path = "/path/to/coworkers-project",
)
```

If your coworker has a target `//foo:bar`, your project can refer to it as
`@coworkers_project//foo:bar`. External project names must be
[valid workspace names](/rules/lib/globals#workspace).

### Depending on non-Bazel projects {:#non-bazel-projects}

Rules prefixed with `new_`, such as
[`new_local_repository`](/reference/be/workspace#new_local_repository),
allow you to create targets from projects that do not use Bazel.

For example, suppose you are working on a project, `my-project/`, and you want
to depend on your coworker's project, `coworkers-project/`. Your coworker's
project uses `make` to build, but you'd like to depend on one of the .so files
it generates. To do so, add the following to `my_project/WORKSPACE`:

```python
new_local_repository(
    name = "coworkers_project",
    path = "/path/to/coworkers-project",
    build_file = "coworker.BUILD",
)
```

`build_file` specifies a `BUILD` file to overlay on the existing project, for
example:

```python
cc_library(
    name = "some-lib",
    srcs = glob(["**"]),
    visibility = ["//visibility:public"],
)
```

You can then depend on `@coworkers_project//:some-lib` from your project's
`BUILD` files.

### Depending on external packages {:#external-packages}

#### Maven artifacts and repositories {:#maven-repositories}

Use the ruleset [`rules_jvm_external`](https://github.com/bazelbuild/rules_jvm_external){: .external}
to download artifacts from Maven repositories and make them available as Java
dependencies.

## Fetching dependencies {:#fetching-dependencies}

By default, external dependencies are fetched as needed during `bazel build`. If
you would like to prefetch the dependencies needed for a specific set of targets, use
[`bazel fetch`](/reference/command-line-reference#commands).
To unconditionally fetch all external dependencies, use
[`bazel sync`](/reference/command-line-reference#commands).
As fetched repositories are [stored in the output base](#layout), fetching
happens per workspace.

## Shadowing dependencies {:#shadowing-dependencies}

Whenever possible, it is recommended to have a single version policy in your
project. This is required for dependencies that you compile against and end up
in your final binary. But for cases where this isn't true, it is possible to
shadow dependencies. Consider the following scenario:

myproject/WORKSPACE

```python
workspace(name = "myproject")

local_repository(
    name = "A",
    path = "../A",
)
local_repository(
    name = "B",
    path = "../B",
)
```

A/WORKSPACE

```python
workspace(name = "A")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "testrunner",
    urls = ["https://github.com/testrunner/v1.zip"],
    sha256 = "...",
)
```

B/WORKSPACE

```python
workspace(name = "B")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "testrunner",
    urls = ["https://github.com/testrunner/v2.zip"],
    sha256 = "..."
)
```

Both dependencies `A` and `B` depend on `testrunner`, but they depend on
different versions of `testrunner`. There is no reason for these test runners to
not peacefully coexist within `myproject`, however they will clash with each
other since they have the same name. To declare both dependencies,
update myproject/WORKSPACE:

```python
workspace(name = "myproject")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
http_archive(
    name = "testrunner-v1",
    urls = ["https://github.com/testrunner/v1.zip"],
    sha256 = "..."
)
http_archive(
    name = "testrunner-v2",
    urls = ["https://github.com/testrunner/v2.zip"],
    sha256 = "..."
)
local_repository(
    name = "A",
    path = "../A",
    repo_mapping = {"@testrunner" : "@testrunner-v1"}
)
local_repository(
    name = "B",
    path = "../B",
    repo_mapping = {"@testrunner" : "@testrunner-v2"}
)
```

This mechanism can also be used to join diamonds. For example if `A` and `B`
had the same dependency but call it by different names, those dependencies can
be joined in myproject/WORKSPACE.

## Overriding repositories from the command line {:#overriding-repositories}

To override a declared repository with a local repository from the command line,
use the
[`--override_repository`](/reference/command-line-reference#flag--override_repository)
flag. Using this flag changes the contents of external repositories without
changing your source code.

For example, to override `@foo` to the local directory `/path/to/local/foo`,
pass the `--override_repository=foo=/path/to/local/foo` flag.

Some of the use cases include:

* Debugging issues. For example, you can override a `http_archive` repository
  to a local directory where you can make changes more easily.
* Vendoring. If you are in an environment where you cannot make network calls,
  override the network-based repository rules to point to local directories
  instead.

## Using proxies {:#using-proxies}

Bazel will pick up proxy addresses from the `HTTPS_PROXY` and `HTTP_PROXY`
environment variables and use these to download HTTP/HTTPS files (if specified).

## Support for IPv6 {:#support-for-ipv6}

On IPv6-only machines, Bazel will be able to download dependencies with
no changes. On dual-stack IPv4/IPv6 machines, however, Bazel follows the same
convention as Java: if IPv4 is enabled, IPv4 is preferred. In some situations,
for example when IPv4 network is unable to resolve/reach external addresses,
this can cause `Network unreachable` exceptions and build failures.
In these cases, you can override Bazel's behavior to prefer IPv6
by using [`java.net.preferIPv6Addresses=true` system property](https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html){: .external}.
Specifically:

* Use `--host_jvm_args=-Djava.net.preferIPv6Addresses=true`
  [startup option](/docs/user-manual#startup-options),
  for example by adding the following line in your
  [`.bazelrc` file](/run/bazelrc):

  `startup --host_jvm_args=-Djava.net.preferIPv6Addresses=true`

* If you are running Java build targets that need to connect to the internet
  as well (integration tests sometimes needs that), also use
  `--jvmopt=-Djava.net.preferIPv6Addresses=true`
  [tool flag](/docs/user-manual#jvmopt), for example by having the
  following line in your [`.bazelrc` file](/run/bazelrc):

  `build --jvmopt=-Djava.net.preferIPv6Addresses`

* If you are using
  [rules_jvm_external](https://github.com/bazelbuild/rules_jvm_external){: .external},
  for example, for dependency version resolution, also add
  `-Djava.net.preferIPv6Addresses=true` to the `COURSIER_OPTS`
  environment variable to [provide JVM options for Coursier](https://github.com/bazelbuild/rules_jvm_external#provide-jvm-options-for-coursier-with-coursier_opts){: .external}

## Transitive dependencies {:#transitive-dependencies}

Bazel only reads dependencies listed in your `WORKSPACE` file. If your project
(`A`) depends on another project (`B`) which lists a dependency on a third
project (`C`) in its `WORKSPACE` file, you'll have to add both `B`
and `C` to your project's `WORKSPACE` file. This requirement can balloon the
`WORKSPACE` file size, but limits the chances of having one library
include `C` at version 1.0 and another include `C` at 2.0.

## Caching of external dependencies {:#caching-external-dependencies}

By default, Bazel will only re-download external dependencies if their
definition changes. Changes to files referenced in the definition (such as patches
or `BUILD` files) are also taken into account by bazel.

To force a re-download, use `bazel sync`.

## Layout {:#layout}

External dependencies are all downloaded to a directory under the subdirectory
`external` in the [output base](/remote/output-directories). In case of a
[local repository](/reference/be/workspace#local_repository), a symlink is created
there instead of creating a new directory.
You can see the `external` directory by running:

```posix-terminal
ls $(bazel info output_base)/external
```

Note that running `bazel clean` will not actually delete the external
directory. To remove all external artifacts, use `bazel clean --expunge`.

## Offline builds {:#offline-builds}

It is sometimes desirable or necessary to run a build in an offline fashion. For
simple use cases, such as traveling on an airplane,
[prefetching](#fetching-dependencies) the needed
repositories with `bazel fetch` or `bazel sync` can be enough; moreover, the
using the option `--nofetch`, fetching of further repositories can be disabled
during the build.

For true offline builds, where the providing of the needed files is to be done
by an entity different from bazel, bazel supports the option
`--distdir`. Whenever a repository rule asks bazel to fetch a file via
[`ctx.download`](/rules/lib/repository_ctx#download) or
[`ctx.download_and_extract`](/rules/lib/repository_ctx#download_and_extract)
and provides a hash sum of the file
needed, bazel will first look into the directories specified by that option for
a file matching the basename of the first URL provided, and use that local copy
if the hash matches.

Bazel itself uses this technique to bootstrap offline from the [distribution
artifact](https://github.com/bazelbuild/bazel-website/blob/master/designs/_posts/2016-10-11-distribution-artifact.md).
It does so by [collecting all the needed external
dependencies](https://github.com/bazelbuild/bazel/blob/5cfa0303d6ac3b5bd031ff60272ce80a704af8c2/WORKSPACE#L116){: .external}
in an internal
[`distdir_tar`](https://github.com/bazelbuild/bazel/blob/5cfa0303d6ac3b5bd031ff60272ce80a704af8c2/distdir.bzl#L44){: .external}.

However, bazel allows the execution of arbitrary commands in repository rules,
without knowing if they call out to the network. Therefore, bazel has no option
to enforce builds being fully offline. So testing if a build works correctly
offline requires external blocking of the network, as bazel does in its
bootstrap test.

## Best practices {:#best-practices}

### Repository rules {:#repository-rules}

A repository rule should generally be responsible for:

-  Detecting system settings and writing them to files.
-  Finding resources elsewhere on the system.
-  Downloading resources from URLs.
-  Generating or symlinking BUILD files into the external repository directory.

Avoid using `repository_ctx.execute` when possible. For example, when using a non-Bazel C++
library that has a build using Make, it is preferable to use `repository_ctx.download()` and then
write a BUILD file that builds it, instead of running `ctx.execute(["make"])`.

Prefer [`http_archive`](/rules/lib/repo/http#http_archive) to `git_repository` and
`new_git_repository`. The reasons are:

* Git repository rules depend on system `git(1)` whereas the HTTP downloader is built
  into Bazel and has no system dependencies.
* `http_archive` supports a list of `urls` as mirrors, and `git_repository` supports only
  a single `remote`.
* `http_archive` works with the [repository cache](/run/build#repository-cache), but not
  `git_repository`. See
   [#5116](https://github.com/bazelbuild/bazel/issues/5116){: .external} for more information.

Do not use `bind()`. See "[Consider removing
bind](https://github.com/bazelbuild/bazel/issues/1952){: .external}" for a long
discussion of its issues and alternatives.
