package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.fhirpath.ExpressionNode;
import org.hl7.fhir.r5.fhirpath.FHIRPathEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * An expression compiled into paths the database can run — one that answers
 * true or false, or one that yields values
 * (REQ-DBO-VAL-AN-INVARIANT-IS-COMPILED-WHEN-IT-ARRIVES,
 * REQ-DBO-VER-AN-EXPRESSION-THAT-YIELDS-A-VALUE-IS-COMPILED).
 *
 * <p>Once, when the definition arrives, because the answer is the same every
 * time and the thing that will run it is a database. Two shapes come out:
 * a jsonpath boolean expression, for an invariant evaluated against an
 * element instance; and jsonpath selections, for a search parameter whose
 * values are read out of a document. They share one walk, because a search
 * expression's {@code where()} is an invariant's condition and an invariant's
 * path is a search expression's start.
 *
 * <p><b>Parsed by the toolchain's own parser.</b> Not to save writing one: the
 * point of this whole line of work is that the two agree, and compiling from
 * the same tree the toolchain interprets is the only version of that claim
 * anybody can check. What is ours is the walk from that tree to a path.
 *
 * <p><b>What it refuses, it refuses by name.</b> An expression using something
 * this cannot express is returned as a refusal naming the function or operator
 * that stopped it. The alternative — a compiler that quietly emits something
 * weaker — is a checker that reports conformance it never established, or an
 * index that answers a search with part of the truth.
 */
final class ExpressionPaths {

    private ExpressionPaths() {}

    /** A compiled question, or the reason it is not one. */
    record Predicate(String path, String why) {

        static Predicate of(String path) {
            return new Predicate(path, null);
        }

        static Predicate refused(String why) {
            return new Predicate(null, why);
        }

        boolean enforceable() {
            return path != null;
        }
    }

    /**
     * A compiled selection: the paths whose items are the values, or — for an
     * expression that yields a truth rather than items, {@code deceased.exists()}
     * — the predicate whose answer is the one value. Or the reason it is
     * neither.
     */
    record Selection(List<String> paths, String predicate, String why) {

        static Selection refused(String why) {
            return new Selection(List.of(), null, why);
        }

        boolean enforceable() {
            return !paths.isEmpty() || predicate != null;
        }
    }

    /**
     * The parser, over a context with nothing in it.
     *
     * <p>Parsing is lexical: it reads an expression into a tree and asks the
     * context nothing. Building one of these needs a context, and giving it an
     * empty one is what keeps compiling a definition from needing the
     * definitions.
     */
    private static final class Parser {
        private static final FHIRPathEngine ENGINE = engine();

        private static FHIRPathEngine engine() {
            try {
                return new FHIRPathEngine(
                        new org.hl7.fhir.r5.context.SimpleWorkerContext.SimpleWorkerContextBuilder()
                                .fromNothing());
            } catch (Exception unavailable) {
                throw new IllegalStateException(
                        "the toolchain's expression parser could not be built, so no expression "
                        + "can be compiled", unavailable);
            }
        }
    }

    private static ExpressionNode parse(String expression) {
        return Parser.ENGINE.parse(expression);
    }

    // ============================================================ questions

    static Predicate predicate(String expression) {
        if (expression == null || expression.isBlank()) {
            return Predicate.refused("it states no expression");
        }
        ExpressionNode parsed;
        try {
            parsed = parse(expression);
        } catch (Exception unparseable) {
            return Predicate.refused("the toolchain cannot read it: " + unparseable.getMessage());
        }
        try {
            return Predicate.of(bool(parsed, "$"));
        } catch (Untranslatable why) {
            return Predicate.refused(why.getMessage());
        }
    }

    // =========================================================== selections

    /**
     * The values an expression picks out of a document of the given type.
     *
     * <p>A search expression names the type it starts from —
     * {@code Patient.name} — and that first step is the document itself, so
     * it is walked past. An expression that turns out to be a question is
     * compiled as one and its answer is the value, which is what a token
     * parameter over {@code deceased.exists()} means.
     */
    static Selection selection(String expression, String resourceType) {
        return selection(expression, resourceType, java.util.Map.of());
    }

    /**
     * The same, with the version's choice elements in hand.
     *
     * <p>A choice is one element in the model an expression is written
     * against and several keys in the document it runs over, so the paths are
     * spelled out afterwards: {@code Observation.effective} is compiled once
     * and comes out as one path per type the element may take.
     */
    static Selection selection(String expression, String resourceType,
            java.util.Map<String, List<String>> choices) {
        if (expression == null || expression.isBlank()) {
            return Selection.refused("it states no expression");
        }
        ExpressionNode parsed;
        try {
            parsed = parse(expression);
        } catch (Exception unparseable) {
            return Selection.refused("the toolchain cannot read it: " + unparseable.getMessage());
        }
        Untranslatable notAPath;
        try {
            return new Selection(spelledOut(paths(parsed, resourceType), resourceType, choices),
                    null, null);
        } catch (Untranslatable why) {
            notAPath = why;
        }
        try {
            return new Selection(List.of(), bool(pastTheType(parsed, resourceType), "$"), null);
        } catch (Untranslatable notAQuestionEither) {
            return Selection.refused(notAPath.getMessage());
        }
    }

    /**
     * Every branch of a union, each walked from the document.
     *
     * <p>A parameter shared by several types unions one branch per type —
     * {@code Patient.name | Person.name} — and for a document of one type the
     * other types' branches select nothing. They are passed over rather than
     * walked, and an expression none of whose branches is about this type
     * is refused rather than compiled into a path that starts at a key that
     * is a type name.
     */
    private static List<String> paths(ExpressionNode node, String resourceType) {
        List<String> out = new ArrayList<>();
        boolean mine = false;
        ExpressionNode branch = node;
        while (branch != null) {
            if (aboutAnotherType(branch, resourceType)) {
                // Another type's branch, narrowed or not, is passed over
                // whole: `(DeviceRequest.code as CodeableConcept)` in a
                // parameter shared by twelve types says nothing about the
                // eleven others.
                ExpressionNode after = branch.getOperation() == ExpressionNode.Operation.As
                        ? branch.getOpNext() : branch;
                if (after.getOperation() == null) {
                    break;
                }
                if (after.getOperation() != ExpressionNode.Operation.Union) {
                    throw new Untranslatable("it joins a branch about another type with '"
                            + after.getOperation().toCode() + "'");
                }
                branch = after.getOpNext();
                continue;
            }
            // A parenthesised branch — `(Observation.value as Quantity)` — is
            // its own union to walk, and the operator after the closing
            // parenthesis belongs to the group.
            List<String> here;
            if (branch.getKind() == ExpressionNode.Kind.Group) {
                try {
                    here = paths(branch.getGroup(), resourceType);
                } catch (Untranslatable inside) {
                    if (!inside.getMessage().startsWith("none of it is about")) {
                        throw inside;
                    }
                    here = List.of(); // a group about another type, passed over
                }
            } else {
                here = walked(pastTheType(branch, resourceType), "$");
            }
            if (branch.getKind() != ExpressionNode.Kind.Group) {
                mine = true;
            } else if (!here.isEmpty()) {
                mine = true;
            }
            if (branch.getOperation() == null) {
                out.addAll(here);
                break;
            }
            switch (branch.getOperation()) {
                case Union -> {
                    out.addAll(here);
                    branch = branch.getOpNext();
                }
                case As -> {
                    ExpressionNode type = branch.getOpNext();
                    out.addAll(narrowed(here, typeNamed(type)));
                    // `value as Quantity | value as SampledData`: the union
                    // continues after the type name
                    if (type.getOperation() == null) {
                        branch = null;
                    } else if (type.getOperation() == ExpressionNode.Operation.Union) {
                        branch = type.getOpNext();
                    } else {
                        throw new Untranslatable("it joins a narrowed value with '"
                                + type.getOperation().toCode() + "', which yields no path");
                    }
                }
                default -> throw new Untranslatable("it joins values with '"
                        + branch.getOperation().toCode() + "', which yields no path");
            }
        }
        if (!mine) {
            throw new Untranslatable("none of it is about a " + resourceType);
        }
        return out;
    }

    private static boolean aboutAnotherType(ExpressionNode branch, String resourceType) {
        return resourceType != null && branch.getKind() == ExpressionNode.Kind.Name
                && branch.getInner() != null
                && Character.isUpperCase(branch.getName().charAt(0))
                && !resourceType.equals(branch.getName());
    }

    /**
     * {@code Patient.name} starts at the document: the type is the document.
     *
     * <p>The parser hangs an operator off the HEAD of a dotted term, so
     * {@code Observation.value as Quantity} is the name {@code Observation}
     * carrying {@code as}, and walking past it would drop the operator with
     * it. The operator moves onto the new head. The tree is this call's own,
     * parsed for it, so it can be moved.
     */
    private static ExpressionNode pastTheType(ExpressionNode node, String resourceType) {
        if (resourceType != null && node.getKind() == ExpressionNode.Kind.Name
                && resourceType.equals(node.getName()) && node.getInner() != null) {
            ExpressionNode head = node.getInner();
            head.setOperation(node.getOperation());
            head.setOpNext(node.getOpNext());
            head.setProximal(node.isProximal());
            return head;
        }
        return node;
    }

    /**
     * One branch walked into a path. A step is a name; a function partway
     * narrows the step before it; a function at the end that yields values
     * is applied there; a function at the end that asks a question is not a
     * path, and the caller tries it as one.
     */
    private static List<String> walked(ExpressionNode node, String against) {
        if (node.getKind() == ExpressionNode.Kind.Name && node.getInner() == null
                && node.getOperation() == null && "$".equals(against)
                && Character.isUpperCase(node.getName().charAt(0))) {
            // the whole document: an expression that is only the type
            return List.of("$");
        }
        String path = against;
        ExpressionNode at = node;
        while (at != null) {
            if (at.getKind() == ExpressionNode.Kind.Name) {
                path = path + "." + Json.quoted(at.getName()) + "[*]";
            } else if (at.getKind() == ExpressionNode.Kind.Function) {
                if (asksAQuestion(at)) {
                    throw new Untranslatable("it asks " + at.getName()
                            + "(), which answers rather than selects");
                }
                path = narrowedBy(at, path);
            } else {
                throw new Untranslatable("it walks through something this compiler cannot read");
            }
            at = at.getInner();
        }
        return List.of(path);
    }

    /**
     * A path whose last step is a choice, written once per key it may use.
     *
     * <p>Only the last step, and only when nothing filters it. A choice
     * anywhere but the end has to be narrowed to be walked through — the
     * expression says {@code value as Quantity} and that is a step this
     * compiler already spells concretely — so the end is where an unnarrowed
     * one is, and a path this does not recognise is left exactly as it was.
     */
    private static List<String> spelledOut(List<String> paths, String resourceType,
            java.util.Map<String, List<String>> choices) {
        if (resourceType == null || choices.isEmpty()) {
            return paths;
        }
        List<String> out = new ArrayList<>();
        for (String path : paths) {
            out.addAll(spelledOut(path, resourceType, choices));
        }
        return out;
    }

    private static List<String> spelledOut(String path, String resourceType,
            java.util.Map<String, List<String>> choices) {
        if (!path.endsWith("[*]") || path.indexOf('?') >= 0) {
            return List.of(path);
        }
        StringBuilder dotted = new StringBuilder(resourceType);
        int at = 0;
        while (true) {
            int key = path.indexOf(".\"", at);
            if (key < 0) {
                break;
            }
            int end = path.indexOf('"', key + 2);
            if (end < 0) {
                return List.of(path);
            }
            dotted.append('.').append(path, key + 2, end);
            at = end + 1;
        }
        List<String> keys = choices.get(dotted.toString());
        if (keys == null) {
            return List.of(path);
        }
        String upToTheKey = path.substring(0, path.lastIndexOf(".\"") + 2);
        return keys.stream().map(key -> upToTheKey + key + "\"[*]").toList();
    }

    /** {@code value as Quantity}: the choice under the key that type spells. */
    private static List<String> narrowed(List<String> paths, String type) {
        List<String> out = new ArrayList<>();
        for (String path : paths) {
            out.add(concrete(path, type));
        }
        return out;
    }

    private static String concrete(String path, String type) {
        if (!path.endsWith("[*]")) {
            throw new Untranslatable("it narrows to " + type
                    + " after a filter, which this compiler cannot spell as a key");
        }
        int key = path.lastIndexOf("\".\"");
        int start = path.lastIndexOf(".\"");
        if (start < 0) {
            throw new Untranslatable("it narrows the document itself to " + type);
        }
        String name = path.substring(start + 2, path.length() - 4);
        if (name.indexOf('"') >= 0 || name.indexOf(' ') >= 0) {
            throw new Untranslatable("it narrows to " + type + " after a step this compiler "
                    + "cannot spell as a key");
        }
        return path.substring(0, start) + "." + Json.quoted(name + capitalized(type)) + "[*]";
    }

    private static String typeNamed(ExpressionNode node) {
        if (node == null || node.getKind() != ExpressionNode.Kind.Name || node.getInner() != null) {
            throw new Untranslatable("it narrows to something that is not a type name");
        }
        return node.getName();
    }

    // ------------------------------------------------------- boolean shapes

    /**
     * An expression that answers true or false, as a jsonpath predicate.
     *
     * <p>The operator first. A term is only required to be a question when
     * nothing is being done with it — {@code status = 'final'} is a
     * comparison whose left side is a path, and asking whether that path is a
     * question refuses an expression this compiler can plainly express.
     */
    private static String bool(ExpressionNode node, String against) {
        if (node.getOperation() == null) {
            return boolOf(node, against);
        }
        return switch (node.getOperation()) {
            case And -> "(" + boolOf(node, against) + " && "
                    + bool(node.getOpNext(), against) + ")";
            case Or -> "(" + boolOf(node, against) + " || "
                    + bool(node.getOpNext(), against) + ")";
            // a implies b is "either a does not hold, or b does"
            case Implies -> "(!(" + boolOf(node, against) + ") || "
                    + bool(node.getOpNext(), against) + ")";
            // and xor is "one of them, not both"
            case Xor -> xor(boolOf(node, against), bool(node.getOpNext(), against));
            case Equals -> comparison(node, against, "==");
            case NotEquals -> comparison(node, against, "!=");
            case LessThan -> comparison(node, against, "<");
            case LessOrEqual -> comparison(node, against, "<=");
            case Greater -> comparison(node, against, ">");
            case GreaterOrEqual -> comparison(node, against, ">=");
            case Is -> isType(node, against);
            default -> throw new Untranslatable(
                    "it joins terms with '" + node.getOperation().toCode()
                    + "', which this compiler cannot express");
        };
    }

    private static String xor(String left, String right) {
        return "((" + left + " && !(" + right + ")) || (!(" + left + ") && " + right + "))";
    }

    /**
     * {@code resolve() is Patient}: what a reference points at, by its type.
     *
     * <p>Not a join. A literal reference spells its target's type in its
     * first segment, so the question is answered by the reference string
     * itself; following it would be the reference check's job, not this
     * one's. A type test on anything that is not a reference needs the
     * definition to say what type a value is, and is refused.
     */
    private static String isType(ExpressionNode node, String against) {
        String type = typeNamed(node.getOpNext());
        String path = against;
        ExpressionNode at = node;
        while (at != null && at.getKind() == ExpressionNode.Kind.Name) {
            path = path + "." + Json.quoted(at.getName()) + "[*]";
            at = at.getInner();
        }
        if (at == null || at.getKind() != ExpressionNode.Kind.Function
                || at.getFunction() != ExpressionNode.Function.Resolve || at.getInner() != null) {
            throw new Untranslatable("it asks whether a value is a " + type
                    + ", and only a reference says its type in its own spelling");
        }
        return "(" + path + ".\"reference\" starts with " + Json.quoted(type + "/") + ")";
    }

    /** {@code a = b}, where one side is a path and the other usually a value. */
    private static String comparison(ExpressionNode node, String against, String operator) {
        ExpressionNode other = node.getOpNext();
        if (other.getOperation() != null) {
            throw new Untranslatable("it compares to something that is itself a comparison");
        }
        String counted = counting(node, other, against, operator);
        if (counted != null) {
            return counted;
        }
        return "(" + valueOf(node, against) + " " + operator + " " + valueOf(other, against) + ")";
    }

    /**
     * How many of a thing there are, where the answer is really whether there
     * are any.
     *
     * <p>jsonpath cannot count, and it does not need to: {@code x.count() = 0}
     * is "none of them" and {@code x.count() > 0} is "some of them", which it
     * can say exactly. A count compared to anything else is left to the
     * residue rather than approximated, because a rule about how many is not
     * satisfied by a rule about whether.
     */
    private static String counting(ExpressionNode node, ExpressionNode other, String against,
            String operator) {
        ExpressionNode at = node;
        StringBuilder path = new StringBuilder(against);
        while (at != null && at.getKind() == ExpressionNode.Kind.Name) {
            path.append('.').append(Json.quoted(at.getName())).append("[*]");
            at = at.getInner();
        }
        if (at == null || at.getKind() != ExpressionNode.Kind.Function
                || at.getFunction() != ExpressionNode.Function.Count || at.getInner() != null
                || other.getKind() != ExpressionNode.Kind.Constant) {
            return null;
        }
        String howMany = other.getConstant() == null ? null : other.getConstant().primitiveValue();
        boolean none = "0".equals(howMany);
        boolean one = "1".equals(howMany);
        return switch (operator) {
            case "==" -> none ? "!exists(" + path + ")" : null;
            case "!=" -> none ? "exists(" + path + ")" : null;
            case ">" -> none ? "exists(" + path + ")" : null;
            case ">=" -> one ? "exists(" + path + ")" : null;
            case "<" -> one ? "!exists(" + path + ")" : null;
            default -> null;
        };
    }

    private static String boolOf(ExpressionNode node, String against) {
        if (node.getKind() == ExpressionNode.Kind.Group) {
            String inner = bool(node.getGroup(), against);
            if (node.getInner() != null) {
                throw new Untranslatable("it carries on past a group, which this compiler "
                        + "cannot follow");
            }
            return inner;
        }
        if (node.getKind() == ExpressionNode.Kind.Function || node.getInner() != null) {
            return questionAtTheEnd(node, against);
        }
        throw new Untranslatable("it is not an expression that answers true or false");
    }

    /**
     * A path with a question at the end of it.
     *
     * <p>What a step is depends on where it sits. A name is a step; a
     * {@code where()} in the middle narrows the step before it, which is what
     * a jsonpath filter does; and the function at the END is the question
     * being asked about everything that survived. Walking it in one pass is
     * what lets {@code contained.where(...).empty()} compile rather than
     * being refused for having a function in the middle.
     */
    private static String questionAtTheEnd(ExpressionNode node, String against) {
        String path = against;
        ExpressionNode at = node;
        while (at != null) {
            if (at.getKind() == ExpressionNode.Kind.Name) {
                path = path + "." + Json.quoted(at.getName()) + "[*]";
            } else if (at.getKind() == ExpressionNode.Kind.Function) {
                if (at.getInner() == null) {
                    return applied(at, path);
                }
                if (asksAQuestion(at)) {
                    // a question, and then something asked OF that answer:
                    // `x.exists().not()` is the commonest invariant shape
                    // there is, and stopping at the first function refused
                    // every one of them.
                    return askedOf(applied(at, path), at.getInner());
                }
                path = narrowedBy(at, path);
            } else {
                throw new Untranslatable("it walks through something this compiler cannot read");
            }
            at = at.getInner();
        }
        throw new Untranslatable("it is a path rather than a question about one");
    }

    /** Whether this function answers true or false rather than narrowing a path. */
    private static boolean asksAQuestion(ExpressionNode function) {
        return switch (function.getFunction()) {
            case Empty, Exists, All, Matches, HasValue, StartsWith, Not -> true;
            default -> false;
        };
    }

    /**
     * A function partway along a path: it narrows what the next step walks.
     *
     * <p>Inside the narrowing, the thing under test is the item the step
     * reached, which jsonpath spells {@code @} — so the condition compiles
     * against that rather than against the document.
     */
    private static String narrowedBy(ExpressionNode function, String path) {
        return switch (function.getFunction()) {
            case Where -> path + " ? (" + condition(function, "@") + ")";
            case First -> {
                if (!function.getParameters().isEmpty()) {
                    throw new Untranslatable("first() is given something to do");
                }
                yield path.endsWith("[*]") ? path.substring(0, path.length() - 3) + "[0]" : path;
            }
            // `entry[0]`: an index, which the parser spells as a nameless function
            case Item -> {
                if (function.getParameters().size() != 1
                        || function.getParameters().get(0).getKind() != ExpressionNode.Kind.Constant
                        || !path.endsWith("[*]")) {
                    throw new Untranslatable("it indexes with something that is not a number");
                }
                String index = function.getParameters().get(0).getConstant().primitiveValue();
                if (!index.matches("[0-9]+")) {
                    throw new Untranslatable("it indexes with " + index + ", which is no position");
                }
                yield path.substring(0, path.length() - 3) + "[" + index + "]";
            }
            // the specification's own debugging aid: it says what went past
            // and changes nothing about what is true
            case Trace -> path;
            // extension('url') is the extensions narrowed to that url, which
            // is a filter on a step this compiler already knows
            case Extension -> path + "." + Json.quoted("extension") + "[*] ? (@."
                    + Json.quoted("url") + " == " + regex(function) + ")";
            // ofType(Quantity) / as(Quantity): the choice under the key that
            // type spells
            case OfType, As -> {
                if (function.getParameters().size() != 1) {
                    throw new Untranslatable(function.getName() + "() is given "
                            + function.getParameters().size() + " types");
                }
                yield concrete(path, typeNamed(function.getParameters().get(0)));
            }
            case Resolve -> throw new Untranslatable("it follows a reference with resolve(), "
                    + "which is a join rather than a path");
            default -> throw new Untranslatable("it carries on past " + function.getName()
                    + "(), which this compiler cannot narrow by");
        };
    }

    /**
     * What is asked of an answer.
     *
     * <p>{@code not()} turns it over. {@code trace()} is the specification's
     * own debugging aid and changes nothing about what is true, so it is
     * walked through rather than refused.
     */
    private static String askedOf(String answer, ExpressionNode next) {
        ExpressionNode at = next;
        String so = answer;
        while (at != null) {
            if (at.getKind() != ExpressionNode.Kind.Function) {
                throw new Untranslatable("it reads into the answer to a question");
            }
            so = switch (at.getFunction()) {
                case Not -> "!(" + so + ")";
                case Trace -> so;
                default -> throw new Untranslatable("it asks " + at.getName()
                        + "() of an answer, which this compiler cannot express");
            };
            at = at.getInner();
        }
        return so;
    }

    /** The one condition a function takes, compiled against the item under test. */
    private static String condition(ExpressionNode function, String against) {
        if (function.getParameters().size() != 1) {
            throw new Untranslatable(function.getName() + "() is given "
                    + function.getParameters().size() + " conditions");
        }
        return bool(function.getParameters().get(0), against);
    }

    /** The question at the end: what is being asked about everything that survived. */
    private static String applied(ExpressionNode function, String path) {
        return switch (function.getFunction()) {
            case Empty -> "!exists(" + path + ")";
            case Exists -> function.getParameters().isEmpty()
                    ? "exists(" + path + ")"
                    : "exists(" + path + " ? (" + condition(function, "@") + "))";
            // everything there satisfies it, which is nothing there failing it
            case All -> "!exists(" + path + " ? (!(" + condition(function, "@") + ")))";
            case Matches -> path + " like_regex " + regex(function);
            case StartsWith -> path + " starts with " + regex(function);
            // a primitive with a value is a primitive that is there
            case HasValue -> "exists(" + path + ")";
            case Not -> throw new Untranslatable("it asks not() of a path rather than of a question");
            default -> throw new Untranslatable("it asks " + function.getName()
                    + "(), which this compiler cannot express");
        };
    }

    /** The one string a function is given, as a jsonpath literal. */
    private static String regex(ExpressionNode function) {
        if (function.getParameters().size() != 1) {
            throw new Untranslatable(function.getName() + "() is given "
                    + function.getParameters().size() + " things");
        }
        ExpressionNode given = function.getParameters().get(0);
        if (given.getKind() != ExpressionNode.Kind.Constant) {
            throw new Untranslatable("it gives " + function.getName() + "() something computed");
        }
        return Json.quoted(given.getConstant().primitiveValue());
    }

    // --------------------------------------------------------- value shapes

    /** One side of a comparison: a path into the instance, or a literal. */
    private static String valueOf(ExpressionNode node, String against) {
        if (node.getKind() == ExpressionNode.Kind.Constant) {
            return literal(node);
        }
        if (node.getKind() != ExpressionNode.Kind.Name) {
            throw new Untranslatable("it compares something this compiler cannot read as a value");
        }
        StringBuilder path = new StringBuilder(against);
        ExpressionNode at = node;
        while (at != null) {
            if (at.getKind() != ExpressionNode.Kind.Name) {
                throw new Untranslatable("it compares a path that calls " + at.getName() + "()");
            }
            path.append('.').append(Json.quoted(at.getName()));
            at = at.getInner();
        }
        return path.toString();
    }

    private static String literal(ExpressionNode node) {
        String value = node.getConstant() == null ? null : node.getConstant().primitiveValue();
        if (value == null) {
            throw new Untranslatable("it compares against a value this compiler cannot read");
        }
        if ("%ucum".equals(value) || "ucum".equals(value)) {
            // the one variable that is a constant: the units system's url
            return Json.quoted("http://unitsofmeasure.org");
        }
        if (node.getConstant().fhirType() != null
                && node.getConstant().fhirType().startsWith("%")) {
            throw new Untranslatable("it refers to " + value + ", a variable this compiler "
                    + "does not bind yet");
        }
        if (value.startsWith("%")) {
            throw new Untranslatable("it refers to " + value + ", a variable this compiler "
                    + "does not bind yet");
        }
        return switch (node.getConstant().fhirType() == null ? "string"
                : node.getConstant().fhirType()) {
            case "integer", "decimal" -> value;
            case "boolean" -> value;
            default -> Json.quoted(value);
        };
    }

    private static String capitalized(String type) {
        if (type == null || type.isEmpty()) {
            throw new Untranslatable("a type with no name spells no key");
        }
        return Character.toUpperCase(type.charAt(0)) + type.substring(1);
    }

    /** Thrown where an expression cannot be expressed, caught into a refusal. */
    private static final class Untranslatable extends RuntimeException {
        Untranslatable(String why) {
            super(why);
        }
    }
}
