# Exceptions as values

Status: **designed**, not implemented. The drafter today emits a `THROW` branch with no result; this document
describes what replaces that, and the one Cardinal change it needs.

Every method's result sort becomes a union with `Throwable`. A path that throws does not fail to produce a
result — it produces one in the other arm. That single change makes the graph total, makes `try`/`catch` stop
being a statement form, and turns exception propagation into ordinary data flow.

## Why the current shape is not enough

A Java method can complete abruptly, so `factorial(int) : Int` is a lie: the method returns an `Int` *or* it
throws. The drafter models the second case as a `Branch` whose completion is `THROW` and whose
`initialReceipts` are empty, which is honest about the path but leaves the graph partial. Nothing says what a
throwing path yields, so nothing downstream can reason about a caller that has to cope with it.

Declaring the result as `Int | Throwable` closes that. Every path yields a value of the result sort; the arms
differ, not the totality.

```
factorial(n: Int) : r_0: Int | Throwable
  branch [n < 0]:                     r_0 is in the Throwable arm
  branch [n >= 0 && n == 0]:          r_0 == 1
  branch [n >= 0 && n != 0]:          r_0 == n * r_1
```

## The union is eliminated where it is introduced

The obvious worry is that a union then infects arithmetic — that `Decimal | Throwable` flows into a discharge
and something has to reason about a sum of a number and an exception. It does not, because a call site
destructures immediately:

```
let r_1 = match f(x) on
  [d:Double]    -> g(d)
  [t:Throwable] -> t
```

`d` is bound at sort `Double`, and `g` is a node whose own result sort drives its own reasoning. The union
exists between the call and the match and nowhere else. This is directly expressible in what Cardinal already
has — `SymExpr.Case` carries `CaseBranch(Sort pattern, SymExpr result)`, so the discriminant is a sort pattern
and needs no new node kind.

### A correction worth recording

An earlier reading of this design held that unions break Cardinal's domain routing, on the grounds that
`Discharge.isDecimal` is `"Decimal".equals(sort.name())` and `"Decimal | Throwable"` is not equal to
`"Decimal"` — so a `double`-returning method that can throw would get integer-strict cuts, where `> 0` implies
`>= 1`. Measured, that name mismatch is real:

```
double|Throwable   name = "Decimal | Throwable"
isDense(double)           = true
isDense(double|Throwable) = false
```

But it does not bite, because under the destructuring above a union is never the sort a discharge is keyed on.
The objection was aimed at a design that carries unions into arithmetic, which this is not.

## `try`/`catch` is a match arm, not a statement form

Uncaught and caught are the same mechanism in two positions:

| Java | The Throwable arm returns |
| --- | --- |
| the exception propagates | `t` — which lands in *this* method's own Throwable arm |
| `catch (E e) { … }` | an ordinary value, like any other arm |

So `try`/`catch` needs no special handling in the drafter. It is structurally an `if`/`else` whose discriminant
is a sort rather than a predicate, and in one respect it is simpler: the arms are distinguished by pattern, so
there is no guard to negate. A `throws` clause is the declared shape of the Throwable arm.

**`finally` is the case that does not dissolve.** It runs on every arm, so it is a common suffix spliced into
all of them rather than an arm of its own — and a `finally` that itself returns or throws replaces the arm's
result. That is the remaining complexity in exception handling, and it is worth being clear that it is the
*only* one: `try`/`catch` was the part that looked hard and turned out not to be.

## What `Completion` means now

`Branch.Completion` was added to Cardinal so a path could record that it leaves by throwing and carries no
result equation. With a union result, a throwing path does carry a result, so the flag looks redundant.

It is not. It becomes **the arm selector**: it says which branch of the union a given path produces. A `RETURN`
path produces the value arm; a `THROW` path produces the `Throwable` arm. That is information the drafter
already has and nothing else in the graph records, and the next section needs it.

## The Cardinal change: obligations from refined union branches

Two facts, measured:

- `Sort.refined(name, predicate)` builds a sort carrying only a name and a predicate. Refining a union
  **discards its branches** — `unionBranches()` comes back `null`. So a refinement has to be attached to the
  branch, not to the union: `Int[@>=1] | Throwable`, read as "when it returns normally it is at least 1, and it
  may instead throw".
- `BuiltinIssuer` skips any node whose result sort `isRefined()` is false, and an unrefined union is false. So
  as things stand a union-returning node has no obligation at all and nothing is attempted.

The rule that fixes it:

> A union result sort's obligation is the conjunction over its refined branches. Each branch's obligation is
> discharged only against the paths that can produce that arm — so `Int[@>=1] | Throwable` is attempted on
> `RETURN` paths and is vacuous on `THROW` paths.

This keeps the refutation-only semantics intact, needs no information the drafter does not already have, and
means a method that throws on some paths is not asked to prove a numeric claim about those paths.

## A pre-existing limitation this surfaced

Cardinal chooses one reasoning domain per discharge, from the **node's result sort**:

```java
return isDecimal(resultSort)
        ? DecimalDischarge.discharge(flat, goal)
        : IntegerDischarge.discharge(flat, goal);
```

It has to, because `record Var(String name)` — a `SymExpr` variable carries no sort. The engine cannot ask
which domain `x` lives in.

So a node that returns `Int` while binding a `Double` out of a match will reason about that double with
integer-strict cuts. `int classify(double x)` is enough to hit it; no unions are involved, and the same was true
before this design existed. The fix is a sort environment for variables during discharge, not a change to
routing, and it is deferred rather than solved here.

Worth noting that the destructuring design *helps*: binding `d : Double` at the match records the sort of the
value at exactly the point such an environment would want it.

## Order of work

1. **Cardinal** — obligations from refined union branches, keyed by `Completion` to decide which paths can
   produce which arm.
2. **Concordance** — result sorts become `T | Throwable`; a call is followed by a match binding the value arm;
   the throwing branch's result lands in the `Throwable` arm; `try`/`catch` drafts as arms.
3. **Deferred, tracked here so it is not rediscovered** — per-variable domains, and `finally`.

`JavaSorts.isDense` is currently used nowhere in main code. It was called by a `Refutation` kernel that was
deleted when Concordance consolidated on Cardinal's, and survives as API a test exercises. It becomes live again
only if per-variable domains land.
