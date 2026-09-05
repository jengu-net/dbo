package cloud.jengu.dbo.harness;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * As much of a class file as {@link ReachLedger} has to know: who this is,
 * whose names it carries, and whether something outside this store's own code
 * starts it.
 *
 * <p>Deliberately not a bytecode library. The whole question is which of this
 * store's types appear in a class's constant pool, and every one of them —
 * a construction, a static call, an implemented interface, a field's type, a
 * generic signature — is in there in slash form. Nothing here needs to know
 * what the instructions do.
 */
final class ClassFile {

    /**
     * Own types as the pool writes them. Slash form on purpose: a class named
     * in a string constant is written with dots, so matching slashes is what
     * keeps {@code Class.forName("cloud.jengu.dbo.X")} from counting as a
     * reference — it is one, but it is not one the compiler checked, and a
     * ledger that accepted it would call a reflective mention a mounting.
     */
    private static final Pattern OWN = Pattern.compile("cloud/jengu/dbo/[A-Za-z0-9_/$]+");

    /** Karaf registers an annotated action; nothing in this tree names one. */
    private static final String KARAF_SERVICE = "Lorg/apache/karaf/shell/api/action/lifecycle/Service;";

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_SYNTHETIC = 0x1000;

    private final String name;
    private final int access;
    private final List<String> strings;
    private final boolean entryPoint;

    private ClassFile(String name, int access, List<String> strings, boolean entryPoint) {
        this.name = name;
        this.access = access;
        this.strings = strings;
        this.entryPoint = entryPoint;
    }

    static ClassFile of(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            return null;
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 7, 8, 16, 19, 20 -> {
                    int index = in.readUnsignedShort();
                    if (tag == 7) {
                        classNameIndex[i] = index;
                    }
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 5, 6 -> {
                    in.readLong();
                    // A long or a double takes two pool slots and the second
                    // one is unusable. Skipping it is not an optimisation.
                    i++;
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        int access = in.readUnsignedShort();
        int thisClass = in.readUnsignedShort();
        String name = utf8[classNameIndex[thisClass]].replace('/', '.');
        in.readUnsignedShort();
        int interfaces = in.readUnsignedShort();
        in.skipBytes(interfaces * 2);
        skipMembers(in);
        boolean main = readMethods(in, utf8);
        List<String> strings = new ArrayList<>();
        for (String text : utf8) {
            if (text != null) {
                strings.add(text);
            }
        }
        return new ClassFile(name, access, strings, main || strings.contains(KARAF_SERVICE));
    }

    private static void skipMembers(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.skipBytes(6);
            skipAttributes(in);
        }
    }

    /** @return whether one of them is the {@code main} a launcher calls */
    private static boolean readMethods(DataInputStream in, String[] utf8) throws IOException {
        boolean main = false;
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int access = in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            String descriptor = utf8[in.readUnsignedShort()];
            skipAttributes(in);
            if ((access & ACC_PUBLIC) != 0 && (access & ACC_STATIC) != 0
                    && "main".equals(name) && "([Ljava/lang/String;)V".equals(descriptor)) {
                main = true;
            }
        }
        return main;
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            int length = in.readInt();
            in.skipBytes(length);
        }
    }

    boolean own() {
        return name.startsWith("cloud.jengu.dbo.");
    }

    String topLevel() {
        return ReachLedger.topLevelOf(name);
    }

    /**
     * Whether this class is one the ledger has an opinion about.
     *
     * <p>Public and top-level. A nested type is reached exactly when its
     * owner is, and a package-private class that nothing names is dead code —
     * a real thing to clean up, and a different question from a promise that
     * reads PROVEN.
     */
    boolean judgeable() {
        return (access & ACC_PUBLIC) != 0
                && (access & ACC_SYNTHETIC) == 0
                && !name.contains("$")
                && !name.endsWith(".package-info")
                && !name.endsWith(".module-info");
    }

    /** Started by something that is not this store's code naming it. */
    boolean isEntryPoint() {
        return entryPoint;
    }

    /** Every one of this store's own types this class's pool carries. */
    Set<String> references() {
        Set<String> found = new TreeSet<>();
        for (String text : strings) {
            Matcher matcher = OWN.matcher(text);
            while (matcher.find()) {
                found.add(ReachLedger.topLevelOf(matcher.group()));
            }
        }
        return found;
    }
}
