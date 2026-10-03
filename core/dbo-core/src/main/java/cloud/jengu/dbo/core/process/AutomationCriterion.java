package cloud.jengu.dbo.core.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * When automation may take a step's task: a condition over the task's inputs,
 * written in FHIRPath and compiled when the step is declared.
 *
 * <p><b>Evaluated once, by the store, when the task is authored.</b> A run's
 * inputs are fixed at creation, so the answer cannot change afterwards. The
 * executor cannot be the one to ask: it would have to read the inputs to learn
 * whether it may, and reading them is what a claim confers.
 *
 * <p><b>A small subset, and refused by name outside it.</b> {@code true},
 * {@code false}, and comparisons over a path that starts at a slot —
 * {@code result.interpretation.coding.code = 'N'}, {@code !=}, and
 * {@code .exists()} — joined by {@code and} and {@code or}, {@code and}
 * binding tighter. A comparison holds when the path reaches exactly one value
 * and it equals the literal, which is FHIRPath's equality over a single item;
 * a path that reaches none or several does not equal anything. A condition
 * using anything else is refused when the step is declared, naming what
 * stopped it, rather than evaluated as something weaker.
 *
 * <p><b>It may not read an element that identifies a person.</b> Deciding
 * would mean unsealing it, and deciding eligibility is not a disclosure with a
 * purpose. Which elements identify is the caller's to say, because the person
 * map is the membrane's; a condition naming one is refused at declaration,
 * naming the element.
 */
public final class AutomationCriterion {

    /** A condition that admits every task: what a step declaring none means. */
    public static final AutomationCriterion ALWAYS = new AutomationCriterion("true",
            List.of(List.of(Term.TRUE)));

    private final String expression;
    /** Disjunction of conjunctions. */
    private final List<List<Term>> alternatives;

    private AutomationCriterion(String expression, List<List<Term>> alternatives) {
        this.expression = expression;
        this.alternatives = alternatives;
    }

    /** The condition as it was declared. */
    public String expression() {
        return expression;
    }

    /**
     * Compiles a condition, or refuses it naming why.
     *
     * @param slots       the step's slots, name to the type each holds — a
     *                    path must start at one of them
     * @param identifying whether an element of a type identifies a person,
     *                    asked as (type, top-level element)
     */
    public static AutomationCriterion compile(String expression, Map<String, String> slots,
            BiPredicate<String, String> identifying) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("an automation condition says something; this "
                    + "one is empty");
        }
        List<List<Term>> alternatives = new ArrayList<>();
        for (String alternative : split(expression.trim(), " or ")) {
            List<Term> all = new ArrayList<>();
            for (String term : split(alternative, " and ")) {
                all.add(Term.of(term.trim(), slots, identifying, expression));
            }
            alternatives.add(List.copyOf(all));
        }
        return new AutomationCriterion(expression.trim(), List.copyOf(alternatives));
    }

    /**
     * Whether automation may take a task whose slots hold these values.
     *
     * @param values what each slot holds, as parsed JSON objects — the
     *               record a reference names, or the object given
     */
    public boolean admits(Function<String, List<Object>> values) {
        for (List<Term> all : alternatives) {
            boolean holds = true;
            for (Term term : all) {
                if (!term.holds(values)) {
                    holds = false;
                    break;
                }
            }
            if (holds) {
                return true;
            }
        }
        return false;
    }

    /** The slots the condition reads, so only those are fetched to decide. */
    public Set<String> reads() {
        Set<String> slots = new java.util.LinkedHashSet<>();
        alternatives.forEach(all -> all.forEach(term -> {
            if (term.slot != null) {
                slots.add(term.slot);
            }
        }));
        return slots;
    }

    /**
     * Two conditions are one when they say the same thing. A tenant's spec is
     * read again on every sweep and compared with what is serving, and a
     * condition that was only ever equal to itself made every reading a
     * change — the tenant was rebuilt in place, round after round.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof AutomationCriterion criterion
                && criterion.expression.equals(expression);
    }

    @Override
    public int hashCode() {
        return expression.hashCode();
    }

    @Override
    public String toString() {
        return expression;
    }

    private static List<String> split(String expression, String operator) {
        List<String> parts = new ArrayList<>();
        int from = 0;
        boolean quoted = false;
        for (int at = 0; at < expression.length(); at++) {
            char c = expression.charAt(at);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && expression.startsWith(operator, at)) {
                parts.add(expression.substring(from, at));
                from = at + operator.length();
                at = from - 1;
            }
        }
        parts.add(expression.substring(from));
        return parts;
    }

    /** One comparison, or a constant. */
    private static final class Term {

        static final Term TRUE = new Term(null, List.of(), null, null, true);

        private final String slot;
        private final List<String> path;
        /** "=", "!=", "exists", or null for a constant. */
        private final String operator;
        private final String literal;
        private final boolean constant;

        private Term(String slot, List<String> path, String operator, String literal,
                boolean constant) {
            this.slot = slot;
            this.path = path;
            this.operator = operator;
            this.literal = literal;
            this.constant = constant;
        }

        static Term of(String term, Map<String, String> slots,
                BiPredicate<String, String> identifying, String whole) {
            if (term.equals("true") || term.equals("false")) {
                return new Term(null, List.of(), null, null, term.equals("true"));
            }
            String left;
            String operator;
            String literal = null;
            int notEquals = indexOutsideQuotes(term, "!=");
            int equals = indexOutsideQuotes(term, "=");
            if (notEquals >= 0) {
                left = term.substring(0, notEquals).trim();
                operator = "!=";
                literal = literal(term.substring(notEquals + 2).trim(), whole);
            } else if (equals >= 0) {
                left = term.substring(0, equals).trim();
                operator = "=";
                literal = literal(term.substring(equals + 1).trim(), whole);
            } else if (term.endsWith(".exists()")) {
                left = term.substring(0, term.length() - ".exists()".length()).trim();
                operator = "exists";
            } else {
                throw new IllegalArgumentException("automation condition '" + whole + "': '"
                        + term + "' is not a comparison this store evaluates — it takes "
                        + "<slot>.<path> = 'value', != 'value', .exists(), true and false, "
                        + "joined by and and or");
            }
            String[] segments = left.split("\\.");
            for (String segment : segments) {
                if (!segment.matches("[A-Za-z][A-Za-z0-9]*")) {
                    throw new IllegalArgumentException("automation condition '" + whole
                            + "': '" + segment + "' in '" + left + "' is not an element name; "
                            + "a path is names joined by dots, with no functions in it");
                }
            }
            String slot = segments[0];
            if (!slots.containsKey(slot)) {
                throw new IllegalArgumentException("automation condition '" + whole
                        + "' reads '" + slot + "', which is not a slot of the step; it takes: "
                        + slots.keySet());
            }
            if (segments.length > 1) {
                String type = SlotShape.of(slots.get(slot)).type();
                if (identifying.test(type, segments[1])) {
                    throw new IllegalArgumentException("automation condition '" + whole
                            + "' reads " + type + "." + segments[1] + ", which identifies a "
                            + "person — deciding would mean unsealing it, and whether "
                            + "automation may take a task is not a reason to");
                }
            }
            return new Term(slot, List.of(segments).subList(1, segments.length), operator,
                    literal, false);
        }

        private static int indexOutsideQuotes(String term, String operator) {
            boolean quoted = false;
            for (int at = 0; at < term.length(); at++) {
                char c = term.charAt(at);
                if (c == '\'') {
                    quoted = !quoted;
                } else if (!quoted && term.startsWith(operator, at)) {
                    if (operator.equals("=") && at > 0 && term.charAt(at - 1) == '!') {
                        continue;
                    }
                    return at;
                }
            }
            return -1;
        }

        private static String literal(String said, String whole) {
            if (said.length() >= 2 && said.startsWith("'") && said.endsWith("'")) {
                return said.substring(1, said.length() - 1);
            }
            if (said.matches("-?[0-9]+(\\.[0-9]+)?") || said.equals("true")
                    || said.equals("false")) {
                return said;
            }
            throw new IllegalArgumentException("automation condition '" + whole + "': '" + said
                    + "' is not a literal; a value is quoted, or a number, or true or false");
        }

        boolean holds(Function<String, List<Object>> values) {
            if (slot == null) {
                return constant;
            }
            List<Object> reached = new ArrayList<>(values.apply(slot));
            for (String element : path) {
                List<Object> next = new ArrayList<>();
                for (Object at : reached) {
                    if (at instanceof Map<?, ?> object && object.get(element) != null) {
                        Object value = object.get(element);
                        if (value instanceof List<?> many) {
                            next.addAll(many);
                        } else {
                            next.add(value);
                        }
                    }
                }
                reached = next;
            }
            return switch (operator) {
                case "exists" -> !reached.isEmpty();
                case "=" -> reached.size() == 1 && String.valueOf(reached.get(0)).equals(literal);
                default -> reached.size() == 1
                        && !String.valueOf(reached.get(0)).equals(literal);
            };
        }
    }
}
