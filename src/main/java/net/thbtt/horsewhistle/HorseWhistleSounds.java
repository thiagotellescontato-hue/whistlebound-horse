package net.thbtt.horsewhistle;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;

import java.util.List;

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

    public static SoundEvent random(Random random) {
        return WHISTLES.get(random.nextInt(WHISTLES.size()));
    }

    private static SoundEvent register(String name) {
        Identifier id = HorseWhistle.id(name);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }
}