load("//tools/build_defs/repo:http.bzl", "http_archive", "http_file", "http_jar")
load("//:distdir.bzl", "dist_http_archive", "dist_http_file", "distdir_tar")
load("//:distdir_deps.bzl", "DIST_DEPS")
load("@io_bazel_skydoc//:setup.bzl", "stardoc_repositories")
load("@io_bazel_rules_sass//:package.bzl", "rules_sass_dependencies")
load("@build_bazel_rules_nodejs//:index.bzl", "node_repositories")
load("@io_bazel_rules_sass//:defs.bzl", "sass_repositories")
load("@rules_pkg//:deps.bzl", "rules_pkg_dependencies")
load("//src/main/res:winsdk_configure.bzl", "winsdk_configure")
load("@local_config_winsdk//:toolchains.bzl", "register_local_rc_exe_toolchains")
load("@com_github_grpc_grpc//bazel:grpc_deps.bzl", "grpc_deps")
load("@com_github_grpc_grpc//bazel:grpc_extra_deps.bzl", "grpc_extra_deps")
load("//tools/distributions/debian:deps.bzl", "debian_deps")
load("@bazel_skylib//:workspace.bzl", "bazel_skylib_workspace")

def bazel_deps2():

    stardoc_repositories()
    rules_sass_dependencies()
    node_repositories()
    sass_repositories()
    register_execution_platforms("//:default_host_platform")  # buildozer: disable=positional-args

    # Tools for building deb, rpm and tar files.
    dist_http_archive(
        name = "rules_pkg",
    )

    

    rules_pkg_dependencies()

    # Toolchains for Resource Compilation (.rc files on Windows).
    

    winsdk_configure(name = "local_config_winsdk")

    

    register_local_rc_exe_toolchains()

    register_toolchains("//src/main/res:empty_rc_toolchain")

    dist_http_archive(
        name = "com_github_grpc_grpc",
    )

    # Override the abseil-cpp version defined in grpc_deps(), which doesn't work on latest macOS
    # Fixes https://github.com/bazelbuild/bazel/issues/15168
    dist_http_archive(
        name = "com_google_absl",
    )

    # Projects using gRPC as an external dependency must call both grpc_deps() and
    # grpc_extra_deps().

    grpc_deps()

    

    grpc_extra_deps()

    

    debian_deps()

    

    bazel_skylib_workspace()
