# libsu service component

This directory contains the service-side component based on
[libsu](https://github.com/topjohnwu/libsu), version **6.0.0**.

## License

The libsu-derived component is distributed under the Apache License 2.0.
The license text is provided in this directory as `LICENSE`.
Original upstream copyright and license notices in source files are retained.

## Source modifications

The following upstream source files have been modified by the Density Fold project:

- `src/main/java/com/topjohnwu/superuser/ipc/RootService.java`
- `src/main/java/com/topjohnwu/superuser/internal/RootServiceManager.java`

Modification notices are included in those files. The notices identify them as
modified versions of the corresponding libsu 6.0.0 sources.

## Bundled binary

`main.jar` is the upstream libsu 6.0.0 binary. It is included unchanged from
the upstream release; it is not a Density Fold reimplementation.

Upstream project: https://github.com/topjohnwu/libsu
Upstream version: 6.0.0
License: Apache-2.0
