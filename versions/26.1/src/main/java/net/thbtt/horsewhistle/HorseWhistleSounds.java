package net.thbtt.horsewhistle;

import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;

public final class HorseWhistleSounds {
    public static final SoundEvent WHISTLE_01 = register("whistle01");
    public static final SoundEvent WHISTLE_02 = register("whistle02");
    public static final SoundEvent WHISTLE_03 = register("whistle03");
    public static final SoundEvent WHISTLE_04 = register("whistle04");

    private static final List<SoundEvent> WHISTLES = List.of(
            WHISTLE_01,
            WHISTLE_02,
            WHISTLE_03,
            WHISTLE_04
    );

    private HorseWhistleSounds() {
    }

    public static void init() {
    }

    public static SoundEvent random(RandomSource random) {
        return WHISTLES.get(random.nextInt(WHISTLES.size()));
    }

    private static SoundEvent register(String name) {
        Identifier id = HorseWhistle.id(name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }
}