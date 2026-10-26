# resultMap and result mapping validation

Detailed behavior of the rules that check `<resultMap>` declarations and the
references to them. The README only lists these rules; this document records
exactly what each one checks and when it stays silent (fail-open).

All of these rules work only from the mapper XML and Java sources in the
workspace. MyBatis configuration, annotations, and compiled classes are not
loaded. A fully-qualified reference such as `other.CommonMapper.BaseMap` whose
namespace has no mapper XML in the workspace (for example, it lives in another
module or a jar) is never reported, because its contents cannot be checked.
Missing IDs in namespaces that are in the workspace are still reported, so a
resultMap registered there by other means (annotations, Java code) can produce
a warning. Warnings do not change the CLI exit code.

## Explicit resultMap property validation

For example, `<resultMap type="com.example.User">` with
`<result column="name" property="naem"/>` produces a warning if the workspace
class `com.example.User` has neither a `naem` field nor a `setNaem(...)` method.
Private fields are supported, and property names are case-sensitive.

The same check applies to direct `<id>` / `<result>` children of inline
`<association javaType="com.example.Address">` and
`<collection ofType="com.example.Item">`, including deeper nesting inside
top-level resultMaps. Each mapping uses its immediate parent's
type: collection `javaType` describes the container and is not used as the
element type. An explicit nested type can be checked even when its enclosing
mapping's type cannot be resolved. The association/collection's own `property`
is also checked against its immediate parent's type, not against its
own `javaType`/`ofType`. For example, an association with `property="addres"`
warns if the enclosing Java class has neither that field nor a matching setter.
This parent-property check does not require the association/collection to declare
its own type. Type compatibility is not checked.

When an inline association **omits** `javaType`, its type can also be inferred
from the immediate parent Java class's field or one-argument setter. For example,
if `com.example.User` declares `private Address address;`, then
`<association property="address"><result property="stret"/></association>`
warns against the resolved `Address` class. Fully-qualified Java declarations,
explicit imports, and same-package simple names are supported. Both owner and
target must satisfy the plain workspace-class constraints below. Conflicting
field/setter types, overloaded setters with different types, generic/array types,
static members, nested member types, and ambiguous imports remain fail-open;
simple names with wildcard/static imports are not resolved. Deeper association
chains, associations inside explicitly typed collections, and explicit inline
cases are supported. An existing `javaType` attribute (even blank, an alias, or
dynamic) always takes precedence and never triggers inference.
Collection element types and omitted case types are still not inferred.
Association/collection properties are also checked when the element specifies
`resultMap` or `select`: these attributes do not change the property's owner.
For example, `<association property="addres" resultMap="AddressMap"/>` warns
against the enclosing Java type even if `AddressMap` resolves correctly.
This check is independent of reference resolution; a missing resultMap can also
produce the existing unresolved-reference warning.
Descendants of mappings with `resultMap` or `select` are still skipped; referenced
maps and nested select return types are not expanded or inferred. Mappings with
`extends` and their descendants are skipped.

Direct `<id>` / `<result>` / `<association>` / `<collection>` properties inside
an inline discriminator `<case resultType="com.example.Admin">` are checked
against that explicit case type, not the enclosing resultMap type or the
discriminator's `javaType`. For example, `<result property="roel"/>` warns if
`com.example.Admin` has no `roel` field or setter. Deeper inline mappings use
their immediate parent's explicit or inferred type, including cases inside
nested mappings. Omitted case types are not inferred. Cases with a `resultMap`
attribute (even blank), and descendants of referenced/selected/inherited mappings,
are skipped. Properties outside inline cases in resultMaps containing
discriminators remain fail-open; a property owner containing another discriminator
is also skipped rather than expanding its possible case types.

This is a conservative first step toward result mapping validation, not SQL
column validation. It skips aliases (including simple class names), unresolved
or duplicate Java types, interfaces, abstract/generic/nested classes, records,
classes with inheritance or annotations (including annotated members and
Lombok), `resultMap extends`, discriminated mappings outside explicit inline cases,
blank/dynamic/nested/indexed properties,
constructor mappings, and properties whose immediate parent has no explicit
or safely inferred supported FQCN type. No MyBatis
configuration or compiled classpath is loaded.

## resultMap inheritance references

`<resultMap id="UserDetails" type="map" extends="BaseUser"/>` produces
`mybatis-lint/unresolved-result-map-extends` if `BaseUser` is not declared as a
top-level resultMap in the same namespace anywhere in the scanned workspace.
Fully-qualified parents such as `com.example.OtherMapper.BaseUser` are also
resolved across files, including forward references.

This rule checks parent existence at each link, not inheritance cycles or inherited
properties. Blank/dynamic (`${...}`) parents and missing/dynamic namespaces are
skipped. Nested mappings and duplicate
resultMap IDs are not checked by this rule. MyBatis configuration, annotations,
and external dependencies are not loaded, so an externally registered parent
may produce a warning; warnings do not change the CLI exit code.

## resultMap inheritance cycles

`<resultMap id="A" type="map" extends="B"/>` and
`<resultMap id="B" type="map" extends="A"/>` produce
`mybatis-lint/result-map-inheritance-cycle` warnings at each participating
`extends` attribute value. Self-references are also detected. Short parents
resolve within the current namespace; fully-qualified parents resolve across
workspace files. Current-namespace-qualified declaration IDs are normalized.
Single-file analysis detects local cycles too.

Only cycle members are reported, not resultMaps that merely inherit from a cycle.
Blank/dynamic IDs, parents, or namespaces, nested declarations, malformed/unreadable
XML, and paths through duplicate resultMap IDs are skipped. Missing parents remain
the responsibility of `mybatis-lint/unresolved-result-map-extends`. Configuration,
annotations, and external dependencies are not loaded; inherited properties are
not checked. These warnings do not change the CLI exit code.

## Statement resultMap references

`<select id="find" resultMap="UserMap"/>` produces
`UNRESOLVED_STATEMENT_RESULT_MAP` (`mybatis-lint/unresolved-statement-result-map`,
WARNING) if `UserMap` is not declared as a
top-level resultMap in the current namespace anywhere in the scanned workspace.
The same check applies to top-level `<insert>`, `<update>`, and `<delete>`.
Fully-qualified `namespace.resultMapId` references and forward declarations are
supported. A missing ID produces a warning at the `resultMap` attribute value.
Duplicate local IDs are collapsed to a single entry; later declarations do not
affect this existence check.

Fail-open: blank values, attributes containing `${...}` or a comma
(multiple resultMaps), and missing/dynamic namespaces are skipped.
A nonblank `resultType` also skips this check to avoid duplicating the existing
`RESULT_MAP_AND_TYPE` warning. Malformed/unreadable mapper XML is handled by the
normal XML analysis, not reported again by this rule.
This validates existence only, not mapping compatibility;
nested mappings and duplicate ID diagnostics remain out of scope.
A type alias such as
`map` is not a resultMap reference unless a resultMap with that ID is declared.
Externally registered resultMaps may produce warnings because configuration,
annotations, and dependencies are not loaded. Warnings do not change the CLI
exit code.

## Nested mapping type aliases

Inside top-level resultMaps, explicit `javaType` values on `<association>` /
`<collection>` and `ofType` values on `<collection>` use the existing
`mybatis-lint/unresolved-type-alias` heuristic (warning). Deeper nested mappings
are included. For example, `<collection property="items" ofType="MissingItem"/>`
warns when no workspace class/interface simple name matches `MissingItem`
case-insensitively.

Blank/dynamic values, FQCNs, built-in aliases, and lowercase custom aliases are
skipped. Explicit types are checked even when a nested mapping also references
`resultMap`. Elements outside top-level resultMaps and `association ofType` are
not checked. This does not infer omitted types, validate collection/element
compatibility, or validate properties through aliases. Explicit FQCN nested
properties are checked separately as described in
[Explicit resultMap property validation](#explicit-resultmap-property-validation). External alias registrations can
produce false positives; warnings do not change the CLI exit code. See
[`docs/type-alias-validation.md`](type-alias-validation.md).

## Discriminator type aliases

Inside top-level resultMaps (including deeper inline nesting), explicit
`<discriminator javaType="...">` and direct `<case resultType="...">` children
use the same `mybatis-lint/unresolved-type-alias` heuristic (warning).
For example, `<case value="admin" resultType="MissingAdmin"/>` warns if no
workspace class/interface/record simple name matches `MissingAdmin`,
case-insensitively.

Cases with a `resultMap` attribute are skipped for `resultType` validation:
MyBatis uses the referenced map instead of constructing an inline case map.
Their reference validation remains independent, and the discriminator's own
`javaType` is still checked. Blank/dynamic values, FQCNs, built-in aliases,
lowercase custom aliases, and elements outside top-level resultMaps are skipped.
Discriminator/case types within descendants of association/collection mappings
with `resultMap` or `select`, or cases with `resultMap`, are also skipped:
MyBatis does not construct those inline maps.
Omitted types are not inferred; discriminator values and type compatibility are
not checked. Explicit FQCN inline case properties are checked separately as
described in
[Explicit resultMap property validation](#explicit-resultmap-property-validation). External alias registrations can produce
false positives; warnings do not change the CLI exit code.

## Nested resultMap references

`<association>` and `<collection>` elements inside a top-level `<resultMap>`
are checked when they specify `resultMap="..."`. An unresolved reference produces
`UNRESOLVED_NESTED_RESULT_MAP`
(`mybatis-lint/unresolved-nested-result-map`, WARNING). Local, fully-qualified,
forward, and same-namespace cross-file references are supported.

Blank/dynamic (`${...}`) and comma-separated values, or missing/dynamic
namespaces, are skipped. Inline nested `<resultMap>` declarations are not
interpreted as references. Because configuration, annotations, and dependencies
are not loaded, externally registered resultMaps may produce warnings; warnings
do not change the CLI exit code.

## Discriminator resultMap references

`<discriminator><case value="admin" resultMap="AdminMap"/></discriminator>`
inside a top-level `<resultMap>` produces
`mybatis-lint/unresolved-discriminator-result-map` (warning) if `AdminMap`
is not declared as a top-level resultMap in the current namespace anywhere
in the scanned workspace. Discriminators within inline nested mappings are
also checked. Local, fully-qualified `namespace.resultMapId`, forward, and
same-namespace cross-file references use the existing resultMap resolution.
Duplicate declarations still satisfy this existence check and are reported
separately by the duplicate-ID rule.

Only direct `<case>` children of `<discriminator>` inside top-level resultMaps
are checked. Blank/dynamic (`${...}`) and comma-separated values, missing/dynamic
namespaces, and inline cases without a `resultMap` reference are skipped.
This checks reference existence only, not discriminator values, mapping
compatibility, cycles through cases, or inherited/referenced mapping properties.
Explicit inline case properties use the separate property check described in
[Explicit resultMap property validation](#explicit-resultmap-property-validation).
Malformed/unreadable XML is handled by normal XML analysis. Configuration,
annotations, and external dependencies are not loaded, so externally registered
resultMaps may produce warnings. Warnings do not change the CLI exit code.

## Duplicate resultMap IDs

Top-level `<resultMap id="Base">` declarations must be unique within their
namespace across the scanned workspace. `Base` and `example.Mapper.Base` are
treated as the same ID when the mapper namespace is `example.Mapper`.
`mybatis-lint/duplicate-result-map-id` reports an error at the `id` attribute
value of every conflicting declaration, including the first, so diagnostics
do not depend on which file is analyzed first. Errors cause a non-zero CLI
exit code. Single-file analysis also detects local duplicates.

IDs are case-sensitive, and equal IDs in different namespaces do not conflict.
Missing/blank/dynamic (`${...}`) IDs, missing/blank/dynamic namespaces, nested
declarations, and malformed/unreadable XML are not indexed for this check.
This rule does not change resultMap reference existence checks or inspect
externally registered resultMaps, inheritance cycles, or nested mapping types.
