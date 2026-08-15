package no.beint.riss;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

public final class SpecSets {
    private SpecSets() {
    }

    public static List<SpecSet> load() {
        var specs = new ArrayList<SpecSet>();
        ServiceLoader.load(SpecSet.class).forEach(specs::add);
        specs.sort(Comparator.comparing(SpecSet::name));
        return List.copyOf(specs);
    }

    public static Optional<SpecSet> find(String name) {
        return load().stream().filter(spec -> spec.name().equals(name)).findFirst();
    }

    public static SpecSet required(String name) {
        if (name == null || name.isBlank()) {
            var specs = load();
            if (specs.size() == 1) {
                return specs.getFirst();
            }
            if (specs.isEmpty()) {
                throw new IllegalStateException("No compiled Riss spec is on the classpath");
            }
            throw new IllegalStateException(
                    "Multiple Riss specs are on the classpath; set riss.spec to one of: "
                            + specs.stream().map(SpecSet::name).toList()
            );
        }
        return find(name).orElseThrow(() -> new IllegalStateException("Unknown Riss spec '" + name + "'"));
    }
}
