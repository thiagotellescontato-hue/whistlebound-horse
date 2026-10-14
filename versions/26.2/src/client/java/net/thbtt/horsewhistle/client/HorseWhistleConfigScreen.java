package net.thbtt.horsewhistle.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.thbtt.horsewhistle.network.HorseListPayload;
import net.thbtt.horsewhistle.network.RequestHorseListPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HorseWhistleConfigScreen extends Screen {
    private static final int ROW_SPACING = 44;
    private static int nextPreviewEntityId = -1;

    private final Screen parent;
    private final Map<String, AbstractHorse> horseEntityCache = new HashMap<>();

    private List<HorseListPayload.HorseEntry> horses = List.of();
    private int currentPage = 0;
    private boolean loading = false;
    private boolean missingServer = false;

    public HorseWhistleConfigScreen(Screen parent) {
        super(Component.translatable("screen.horsewhistle.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        requestHorses();
        rebuildWidgets();
    }

    public void setHorses(List<HorseListPayload.HorseEntry> horses) {
        this.horses = List.copyOf(horses);
        this.currentPage = Math.min(this.currentPage, Math.max(0, totalPages() - 1));
        this.loading = false;
        this.missingServer = false;
        this.horseEntityCache.clear();
        rebuildWidgets();
    }

    private AbstractHorse getHorseEntity(HorseListPayload.HorseEntry entry) {
        return this.horseEntityCache.computeIfAbsent(entry.uuid().toString(), key -> {
            if (this.minecraft == null || this.minecraft.level == null) {
                return null;
            }

            if (entry.snbt() != null && !entry.snbt().isBlank()) {
                try {
                    CompoundTag nbt = TagParser.parseCompoundFully(entry.snbt());
                    Entity entity = EntityType.loadEntityRecursive(
                            nbt,
                            this.minecraft.level,
                            new EntitySpawnRequest(EntitySpawnReason.LOAD, false),
                            loadedEntity -> loadedEntity);
                    if (entity instanceof AbstractHorse horse) {
                        assignPreviewEntityId(horse);
                        return horse;
                    }
                } catch (Exception ignored) {
                }
            }

            EntityType<?> horseType = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.fromNamespaceAndPath("minecraft", "horse"));
            AbstractHorse fallback = horseType == null
                    ? null
                    : (AbstractHorse) horseType.create(this.minecraft.level, EntitySpawnReason.LOAD);
            if (fallback != null) {
                assignPreviewEntityId(fallback);
                if (entry.name() != null && !entry.name().isBlank()) {
                    fallback.setCustomName(Component.literal(entry.name()));
                }
            }
            return fallback;
        });
    }

    private static void assignPreviewEntityId(AbstractHorse horse) {
        horse.setId(nextPreviewEntityId--);
    }

    private void requestHorses() {
        this.horses = List.of();

        if (this.minecraft == null || this.minecraft.player == null) {
            this.loading = false;
            this.missingServer = true;
            return;
        }

        if (!ClientPlayNetworking.canSend(RequestHorseListPayload.ID)) {
            this.loading = false;
            this.missingServer = true;
            return;
        }

        this.loading = true;
        this.missingServer = false;

        ClientPlayNetworking.send(RequestHorseListPayload.INSTANCE);
    }

    protected void rebuildWidgets() {
        clearWidgets();

        int centerX = this.width / 2;
        int y = 72;

        List<HorseListPayload.HorseEntry> visibleHorses = visibleHorses();

        for (HorseListPayload.HorseEntry horse : visibleHorses) {
            Button selectButton = Button.builder(Component.literal(buttonLabel(horse)), button -> {
                HorseWhistleClientConfig.INSTANCE.selectHorse(horse.uuid(), horse.name());
                rebuildWidgets();
            }).bounds(centerX + 30, y, 70, 20).build();
            selectButton.active = !isSelected(horse);
            addRenderableWidget(selectButton);

            y += ROW_SPACING;
        }

        int pagesY = this.height - 84;
        addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            if (this.currentPage > 0) {
                this.currentPage--;
                rebuildWidgets();
            }
        }).bounds(centerX - 70, pagesY, 20, 20).build()).active = this.currentPage > 0;

        addRenderableWidget(Button.builder(Component.literal(">"), button -> {
            if (this.currentPage < totalPages() - 1) {
                this.currentPage++;
                rebuildWidgets();
            }
        }).bounds(centerX + 50, pagesY, 20, 20).build()).active = this.currentPage < totalPages() - 1;

        int bottomY = this.height - 56;

        addRenderableWidget(Button.builder(Component.translatable("screen.horsewhistle.refresh"), button -> {
            requestHorses();
            rebuildWidgets();
        }).bounds(centerX - 120, bottomY, 116, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.horsewhistle.clear"), button -> {
            HorseWhistleClientConfig.INSTANCE.clearHorse();
            rebuildWidgets();
        }).bounds(centerX + 4, bottomY, 116, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(centerX - 100, this.height - 30, 200, 20)
                .build());
    }

    private static String buttonLabel(HorseListPayload.HorseEntry horse) {
        return HorseWhistleClientConfig.INSTANCE.selectedHorseUuid != null
                && HorseWhistleClientConfig.INSTANCE.selectedHorseUuid.equals(horse.uuid())
                ? "Selected"
                : "Select";
    }

    private boolean isSelected(HorseListPayload.HorseEntry horse) {
        return HorseWhistleClientConfig.INSTANCE.selectedHorseUuid != null
                && HorseWhistleClientConfig.INSTANCE.selectedHorseUuid.equals(horse.uuid());
    }

    private int totalPages() {
        int horsesPerTab = horsesPerTab();
        return Math.max(1, (this.horses.size() + horsesPerTab - 1) / horsesPerTab);
    }

    private List<HorseListPayload.HorseEntry> visibleHorses() {
        int horsesPerTab = horsesPerTab();
        int start = this.currentPage * horsesPerTab;
        if (start >= this.horses.size()) {
            return List.of();
        }
        int end = Math.min(start + horsesPerTab, this.horses.size());
        return new ArrayList<>(this.horses.subList(start, end));
    }

    private int horsesPerTab() {
        if (this.minecraft == null || this.minecraft.options == null) {
            return 4;
        }

        int guiScale = this.minecraft.options.guiScale().get();

        return switch (guiScale) {
            case 0 -> 2;
            case 1 -> 20;
            case 2 -> 8;
            case 4 -> 2;
            case 3 -> 4;
            default -> 4;
        };
    }

    private Component statusText() {
        if (this.missingServer) {
            return Component.translatable("screen.horsewhistle.server_missing");
        }

        if (this.loading) {
            return Component.translatable("screen.horsewhistle.loading");
        }

        if (this.horses.isEmpty()) {
            return Component.translatable("screen.horsewhistle.no_horses");
        }

        return Component.translatable("screen.horsewhistle.horses_found", this.horses.size());
    }

    private Component selectedText() {
        HorseWhistleClientConfig config = HorseWhistleClientConfig.INSTANCE;

        if (config.selectedHorseUuid == null) {
            return Component.translatable("screen.horsewhistle.selected_none");
        }

        String name = config.selectedHorseName == null || config.selectedHorseName.isBlank()
                ? config.selectedHorseUuid.toString()
                : config.selectedHorseName;

        return Component.translatable("screen.horsewhistle.selected", name);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        graphics.centeredText(this.font, this.title, centerX, 14, 0xFFFFFFFF);
        graphics.centeredText(this.font, selectedText(), centerX, 34, 0xFFA0FFA0);
        graphics.centeredText(this.font, statusText(), centerX, 54, 0xFFA0A0A0);

        int y = 72;
        for (HorseListPayload.HorseEntry entry : visibleHorses()) {
            AbstractHorse horseEntity = getHorseEntity(entry);
            if (horseEntity != null) {
                InventoryScreen.extractEntityInInventoryFollowsMouse(
                        graphics, centerX - 148, y - 20, centerX - 46, y + 38,
                        17, 0.0625F, mouseX, mouseY, horseEntity);

                String name = entry.name();
                if (name.length() > 18) {
                    name = name.substring(0, 16) + "...";
                }
                graphics.text(this.font, name, centerX - 30, y + 12, 0xFFFFFFFF);
            }
            y += ROW_SPACING;
        }

        graphics.centeredText(
                this.font,
                Component.literal((this.currentPage + 1) + " / " + totalPages()),
                centerX,
                this.height - 78,
                0xFFE0E0E0
        );
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(this.parent);
        }
    }
}
