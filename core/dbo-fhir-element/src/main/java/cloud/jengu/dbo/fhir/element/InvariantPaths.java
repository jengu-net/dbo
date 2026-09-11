package cloud.jengu.dbo.fhir.element;

import org.hl7.fhir.r5.fhirpath.ExpressionNode;
import org.hl7.fhir.r5.fhirpath.FHIRPathEngine;

/**
 * An invariant's expression, compiled into a path the database can run
 * (REQ-DBO-VAL-AN-INVARIANT-IS-COMPILED-WHEN-IT-ARRIVES).
 *
 * <p>Once, when the definition arrives, because the answer is the same every
 * time and the thing that will run it is a database. What comes out is a
 * jsonpath boolean expression to be evaluated against an instance of the
 * element the invariant sits on.
 *
 * <p><b>Parsed by the toolchain's own parser.</b> Not to save writing one: the
 * point of this whole line of work is that the two agree, and compiling from
 * the same tree the toolchain interprets is the only version of that claim
 * anybody can check. What is ours is the walk from that tree to a path.
 *
 * <p><b>What it refuses, it refuses by name.</b> An expression using something
 * this cannot express is returned as a refusal naming the function or operator
 * that stopped it, and the invariant is held saying so. The alternative — a
 * compiler that quietly emits something weaker — is a checker that reports
 * conformance it never established.
 */
final class InvariantPaths {

    private InvariantPaths() {}

    /** A compiled invariant, or the reason it is not one. */
    record Compiled(String path, String why) {

        static Compiled of(String path) {
            return new Compiled(path, null);
        }

        static Compiled refused(String why) {
            return new Compiled(null, why);
        }

        boolean enforceable() {
            return path != null;
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
                return new FHIRPathEngine(new org.hl7.fhir.r5.context.SimpleWorkerContext.SimpleWorkerContextBuilder()
                        .fromNothing());
            } catch (Exception unavailable) {
                throw new IllegalStateException(
                        "the toolchain's expression parser could not be built, so no invariant "
                        + "can be compiled", unavailable);
            }
        }
    }

    static Compiled of(String expression) {
        if (expression == null || expression.isBlank()) {
            return Compiled.refused("it states no expression");
        }
        ExpressionNode parsed;
        try {
            parsed = Parser.ENGINE.parse(expression);
        } catch (Exception unparseable) {
            return Compiled.refused("the toolchain cannot read it: " + unparseable.getMessage());
        }
        try {
            return Compiled.of(bool(parsed, "$"));
        } catch (Untranslatable why) {
            return Compiled.refused(why.getMessage());
        }
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
            default -> throw new Untranslatable(
                    "it joins terms with '" + node.getOperation().toCode()
                    + "', which this compiler cannot express");
        };
    }

    private static String xor(String left, String right) {
        return "((" + left + " && !(" + right + ")) || (!(" + left + ") && " + right + "))";
    }

    /** {@code a = b}, where one side is a path and the other usually a value. */
    private static String comparison(ExpressionNode node, String against, String operator) {
        ExpressionNode other = node.getOpNext();
        if (other.getOperation() != null) {
            throw new Untranslatable("it compares to something that is itself a comparison");
        }
        return "(" + valueOf(node, against) + " " + operator + " " + valueOf(other, against) + ")";
    }

    private static String boolOf(ExpressionNode node, String against) {
        if (node.getKind() == ExpressionNode.Kind.Group) {
            String inner = bool(node.getGroup(), against);
            return node.getInner() == null ? inner : chained(node, against, inner);
        }
        if (node.getKind() == ExpressionNode.Kind.Function || node.getInner() != null) {
            return functionOf(node, against);
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
    private static String functionOf(ExpressionNode node, String against) {
        String path = against;
        ExpressionNode at = node;
        while (at != null) {
            if (at.getKind() == ExpressionNode.Kind.Name) {
                path = path + "." + Json.quoted(at.getName()) + "[*]";
            } else if (at.getKind() == ExpressionNode.Kind.Function) {
                if (at.getInner() == null) {
                    return applied(at, path, against);
                }
                path = midway(at, path);
            } else {
                throw new Untranslatable("it walks through something this compiler cannot read");
            }
            at = at.getInner();
        }
        throw new Untranslatable("it is a path rather than a question about one");
    }

    /**
     * A function partway along a path: it narrows what the next step walks.
     *
     * <p>Inside the narrowing, the thing under test is the item the step
     * reached, which jsonpath spells {@code @} — so the condition compiles
     * against that rather than against the document.
     */
    private static String midway(ExpressionNode function, String path) {
        return switch (function.getFunction()) {
            case Where -> path + " ? (" + condition(function, "@") + ")";
            case First -> {
                if (!function.getParameters().isEmpty()) {
                    throw new Untranslatable("first() is given something to do");
                }
                // the step already walked to the members; take the first
                yield path.endsWith("[*]") ? path.substring(0, path.length() - 3) + "[0]" : path;
            }
            default -> throw new Untranslatable("it carries on past " + function.getName()
                    + "(), which this compiler cannot narrow by");
        };
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
    private static String applied(ExpressionNode function, String path, String against) {
        return switch (function.getFunction()) {
            case Empty -> "!exists(" + path + ")";
            case Exists -> function.getParameters().isEmpty()
                    ? "exists(" + path + ")"
                    : "exists(" + path + " ? (" + condition(function, "@") + "))";
            // everything there satisfies it, which is nothing there failing it
            case All -> "!exists(" + path + " ? (!(" + condition(function, "@") + ")))";
            case Matches -> path + " like_regex " + regex(function);
            default -> throw new Untranslatable("it asks " + function.getName()
                    + "(), which this compiler cannot express");
        };
    }

    private static String regex(ExpressionNode function) {
        if (function.getParameters().size() != 1) {
            throw new Untranslatable("matches() is given " + function.getParameters().size()
                    + " things to match");
        }
        ExpressionNode pattern = function.getParameters().get(0);
        if (pattern.getKind() != ExpressionNode.Kind.Constant) {
            throw new Untranslatable("it matches against something computed");
        }
        return Json.quoted(pattern.getConstant().primitiveValue());
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

    private static String chained(ExpressionNode node, String against, String inner) {
        throw new Untranslatable("it carries on past a group, which this compiler cannot follow");
    }

    /** Thrown where an expression cannot be expressed, caught into a refusal. */
    private static final class Untranslatable extends RuntimeException {
        Untranslatable(String why) {
            super(why);
        }
    }
}
