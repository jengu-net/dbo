package cloud.jengu.dbo.tenant;

import cloud.jengu.dbo.runner.transport.Origin;
import cloud.jengu.dbo.sync.ConfigApplication;
import cloud.jengu.dbo.sync.ConfigSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * What a site serves: a place of each tenant elsewhere whose origin it
 * reaches, declared as that tenant is declared there, and whatever the
 * deployment declares beside them.
 *
 * <p>Each declaration read from an origin is written to the directory this
 * source was given, so a site restarted with its link down still serves it.
 *
 * <p><b>An origin that cannot be reached, or no longer answers, is answered
 * with the declaration last kept for it.</b> A place stops being served only
 * when that file is removed. A place with nothing kept and no origin to ask
 * makes the read throw, because empty and unreachable must not look the same.
 */
final class PlacesConfigSource implements ConfigSource {

    private static final Logger LOG = LoggerFactory.getLogger(PlacesConfigSource.class);
    private static final String KEPT = ".json";

    private final Path kept;
    private final ConfigSource beside;
    private final Map<String, Origin> origins = new ConcurrentHashMap<>();
    /** The places whose origin did not answer on the last read, said once until it does. */
    private final Set<String> unanswered = ConcurrentHashMap.newKeySet();
    private volatile TenantRuntimeManager manager;

    /**
     * @param kept   where each place's declaration is kept between reads
     * @param beside what the deployment declares itself, or null when it
     *               declares nothing but places
     */
    PlacesConfigSource(Path kept, ConfigSource beside) {
        this.kept = kept;
        this.beside = beside;
    }

    /** The node whose places these are: told which tenants are places and how each reads its origin. */
    void servedBy(TenantRuntimeManager manager) {
        this.manager = manager;
        origins.values().forEach(this::connect);
    }

    /** An origin this site now reaches with synchronisation on. */
    void reaches(Origin origin) {
        origins.put(origin.tenant(), origin);
        connect(origin);
    }

    /** An origin let go of: the place keeps serving what it holds. */
    void letGo(Origin origin) {
        if (origins.remove(origin.tenant(), origin) && manager != null) {
            manager.readsItsOriginThrough(origin.tenant(), null);
        }
    }

    private void connect(Origin origin) {
        TenantRuntimeManager serving = manager;
        if (serving != null) {
            serving.servesAPlaceOf(origin.tenant());
            serving.readsItsOriginThrough(origin.tenant(),
                    new TenantRuntimeManager.OriginFeeds(origin.records(), origin.definitions(),
                            origin.definitionsWithoutTheFace()));
        }
    }

    @Override
    public Fetch fetch() {
        Set<String> places = new TreeSet<>(origins.keySet());
        places.addAll(keptPlaces());
        List<ConfigApplication.Declared> declared = new ArrayList<>();
        for (String code : places) {
            String declaration = read(code);
            TenantRuntimeManager serving = manager;
            if (serving != null) {
                serving.servesAPlaceOf(code);
            }
            declared.add(new ConfigApplication.Declared(TenantDeclarationModel.TYPE, code,
                    declaration.getBytes(StandardCharsets.UTF_8)));
        }
        boolean complete = true;
        if (beside != null) {
            Fetch own = beside.fetch();
            declared.addAll(own.declarations());
            complete = own.complete();
        }
        declared.addAll(rootsBeside(declared));
        return new Fetch(declared, ConfigSource.markerOf(declared), complete);
    }

    /**
     * A face root for every face a place takes its face through, unless the
     * deployment declares that root itself.
     *
     * <p>The face is most of a tenant's definitions and the same rows wherever
     * the release is the same, so it is read from this node's own release.
     * Declared under the code the place's declaration names, so that
     * declaration stays the origin's own.
     */
    private static List<ConfigApplication.Declared> rootsBeside(
            List<ConfigApplication.Declared> declared) {
        Set<String> named = new TreeSet<>();
        declared.forEach(one -> named.add(one.name()));
        Map<String, String> roots = new java.util.TreeMap<>();
        for (ConfigApplication.Declared one : declared) {
            TenantSpec spec;
            try {
                spec = TenantSpec.parse(new String(one.payload(), StandardCharsets.UTF_8));
            } catch (RuntimeException unreadable) {
                continue; // refused where it is applied, with its reason, not here
            }
            for (TenantSpec.Dependency dependency : spec.dependencies()) {
                if (dependency.face() && !named.contains(dependency.name())) {
                    roots.putIfAbsent(dependency.name(), spec.face());
                }
            }
        }
        List<ConfigApplication.Declared> beside = new ArrayList<>();
        roots.forEach((root, face) -> beside.add(new ConfigApplication.Declared(
                TenantDeclarationModel.TYPE, root, ("""
                        {"code":"%s","face":"%s","faceRoot":true,
                         "types":[
                          {"name":"StructureDefinition","identity":"canonical","handling":"operational"},
                          {"name":"SearchParameter","identity":"canonical","handling":"operational"},
                          {"name":"ValueSet","identity":"canonical","handling":"operational"},
                          {"name":"CodeSystem","identity":"canonical","handling":"operational"}]}"""
                        .formatted(root, face)).getBytes(StandardCharsets.UTF_8))));
        return beside;
    }

    /** The place's declaration from its origin when it answers, else the one kept for it. */
    private String read(String code) {
        Origin origin = origins.get(code);
        if (origin != null) {
            try {
                String declaration = origin.declaration();
                keep(code, declaration);
                if (unanswered.remove(code)) {
                    LOG.info("place {} reads its declaration from its origin again", code);
                }
                return declaration;
            } catch (RuntimeException away) {
                // Down, refused or revoked, the site serves what it was last
                // told; say so once.
                if (unanswered.add(code)) {
                    LOG.warn("place {} could not read its declaration from its origin ({}); "
                            + "serving the one kept", code, away.getMessage());
                }
            }
        }
        Path file = kept.resolve(code + KEPT);
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("place " + code + " has no declaration kept and its "
                    + "origin did not give one, so what it serves cannot be said yet");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("place " + code + ": the kept declaration at " + file
                    + " could not be read", unreadable);
        }
    }

    /** Written whole and moved into place, so a read never meets half a declaration. */
    private void keep(String code, String declaration) {
        Path file = kept.resolve(code + KEPT);
        try {
            if (Files.isReadable(file) && Files.readString(file, StandardCharsets.UTF_8)
                    .equals(declaration)) {
                return;
            }
            Files.createDirectories(kept);
            Path partial = kept.resolve(code + KEPT + ".partial");
            Files.writeString(partial, declaration, StandardCharsets.UTF_8);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unwritable) {
            throw new UncheckedIOException("place " + code + ": its declaration could not be "
                    + "kept at " + file, unwritable);
        }
    }

    private Set<String> keptPlaces() {
        if (!Files.isDirectory(kept)) {
            return Set.of();
        }
        Set<String> codes = new TreeSet<>();
        try (Stream<Path> files = Files.list(kept)) {
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(KEPT))
                    .forEach(name -> codes.add(name.substring(0, name.length() - KEPT.length())));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the places kept at " + kept + " could not be listed",
                    unreadable);
        }
        return codes;
    }
}
