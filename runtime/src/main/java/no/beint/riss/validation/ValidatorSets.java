package no.beint.riss.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

public final class ValidatorSets {
    private ValidatorSets() {
    }

    public static List<ValidatorSet> load() {
        var sets = new ArrayList<ValidatorSet>();
        ServiceLoader.load(ValidatorSet.class).forEach(sets::add);
        return List.copyOf(sets);
    }
}
