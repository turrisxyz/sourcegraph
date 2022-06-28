#                          vvvvvvvvvvvvv bzl_library ref
load("//:bzl_library.bzl", "bzl_library")

#   vvvvv _impl def
def _impl():
    pass

#                  vvvvv _impl ref,solo
bzl_library(impl = _impl)
