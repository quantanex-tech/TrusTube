# Licenses and source references

Application source is GPL-3.0-or-later; see the root LICENSE and source headers.
`NOTICE.txt` gives dependency/artwork attribution and source links. License texts
here are also packaged in the APK; Parent → Open-source notices shows attribution.
`runtime-dependencies.json` records the resolved runtime coordinates and license
metadata. `app/gradle.lockfile` pins resolved versions for reproducible builds.

Basil by Craftwork is CC BY 4.0, separately from the application code. The supplied
SVG collection and derived vectors/PNGs retain attribution; conversion and app tint
are the modifications. Creator's [Basil page](https://craftwork.design/product/basil),
[community source](https://www.figma.com/community/file/931906394678748246),
and [upstream catalog/license metadata](https://github.com/iconify/icon-sets/blob/master/json/basil.json).
The standard CC BY text is copied from SPDX's license-list-data text mirror;
its authoritative license URL is https://creativecommons.org/licenses/by/4.0/.

Apache and MPL texts come from their publishers; jsoup and protobuf license files
come from their pinned source tags. Desugaring texts come from its published source
repository, as linked by its Maven POM. Do not remove copyright headers or packaged
license/notice resources when updating dependencies. Consult upstream sources for
additional copyright details and update this inventory with dependency changes.
