package net.thbtt.horsewhistle.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.text.Text;
import net.thbtt.horsewhistle.network.HorseListPayload;
import net.thbtt.horsewhistle.network.RequestHorseListPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HorseWhistleConfigScreen extends Screen {
    private static final int ROW_SPACING = 44;

    private final Screen parent;
    private final Map<String, AbstractHorseEntity> horseEntityCache = new HashMap<>();

    private List<HorseListPayload.HorseEntry> horses = List.of();
    private int currentPage = 0;
    private boolean loading = false;
    private boolean missingServer = false;

    public HorseWhistleConfigScreen(Screen parent) {
        super(Text.translatable("screen.horsewhistle.title"));
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

    private AbstractHorseEntity getHorseEntity(HorseListPayload.HorseEntry entry) {
        return horseEntityCache.computeIfAbsent(entry.uuid().toString(), k -> {
            if (this.client == null || this.client.world == null) {
                return null;
            }

            if (entry.snbt() != null && !entry.snbt().isBlank()) {
                try {
                    NbtCompound nbt = StringNbtReader.parse(entry.snbt());
                    Entity entity = EntityType.loadEntityWithPassengers(nbt, this.client.world, e -> e);
                    if (entity instanceof AbstractHorseEntity horse) {
                        return horse;
                    }
                } catch (Exception ignored) {
                }
            }

            AbstractHorseEntity fallback = (AbstractHorseEntity) EntityType.HORSE.create(this.client.world);
            if (fallback != null && entry.name() != null && !entry.name().isBlank()) {
                fallback.setCustomName(Text.literal(entry.name()));
            }
            return fallback;
        });
    }

    private void requestHorses() {
        this.horses = List.of();

        if (this.client == null || this.client.player == null) {
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

    private void rebuildWidgets() {
        clearChildren();

        int centerX = this.width / 2;
        int y = 72;

        List<HorseListPayload.HorseEntry> visibleHorses = visibleHorses();

        for (HorseListPayload.HorseEntry horse : visibleHorses) {
            ButtonWidget selectButton = ButtonWidget.builder(Text.literal(buttonLabel(horse)), button -> {
                HorseWhistleClientConfig.INSTANCE.selectHorse(horse.uuid(), horse.name());
                rebuildWidgets();
            }).dimensions(centerX + 30, y, 70, 20).build();
            selectButton.active = !isSelected(horse);
            addDrawableChild(selectButton);

            y += ROW_SPACING;
        }

        int pagesY = this.height - 84;
        addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> {
            if (this.currentPage > 0) {
                this.currentPage--;
                rebuildWidgets();
            }
        }).dimensions(centerX - 70, pagesY, 20, 20).build()).active = this.currentPage > 0;

        addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> {
            if (this.currentPage < totalPages() - 1) {
                this.currentPage++;
                rebuildWidgets();
            }
        }).dimensions(centerX + 50, pagesY, 20, 20).build()).active = this.currentPage < totalPages() - 1;

        int bottomY = this.height - 56;

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.horsewhistle.refresh"), button -> {
            requestHorses();
            rebuildWidgets();
        }).dimensions(centerX - 120, bottomY, 116, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.horsewhistle.clear"), button -> {
            HorseWhistleClientConfig.INSTANCE.clearHorse();
            rebuildWidgets();
        }).dimensions(centerX + 4, bottomY, 116, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(centerX - 100, this.height - 30, 200, 20)
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
        if (this.client == null || this.client.options == null) {
            return 4;
        }

        int guiScale = this.client.options.getGuiScale().getValue();

        return switch (guiScale) {
            case 0 -> 2;
            case 1 -> 20;
            case 2 -> 8;
            case 4 -> 2;
            case 3 -> 4;
            default -> 4;
        };
    }

    private Text statusText() {
        if (this.missingServer) {
            return Text.translatable("screen.horsewhistle.server_missing");
        }

        if (this.loading) {
            return Text.translatable("screen.horsewhistle.loading");
        }

        if (this.horses.isEmpty()) {
            return Text.translatable("screen.horsewhistle.no_horses");
        }

        return Text.translatable("screen.horsewhistle.horses_found", this.horses.size());
    }

    private Text selectedText() {
        HorseWhistleClientConfig config = HorseWhistleClientConfig.INSTANCE;

        if (config.selectedHorseUuid == null) {
            return Text.translatable("screen.horsewhistle.selected_none");
        }

        String name = config.selectedHorseName == null || config.selectedHorseName.isBlank()
                ? config.selectedHorseUuid.toString()
                : config.selectedHorseName;

        return Text.translatable("screen.horsewhistle.selected", name);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 14, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, selectedText(), this.width / 2, 34, 0xA0FFA0);
        context.drawCenteredTextWithShadow(this.textRenderer, statusText(), this.width / 2, 54, 0xA0A0A0);

        int centerX = this.width / 2;
        int y = 72;

        List<HorseListPayload.HorseEntry> visibleHorses = visibleHorses();

        for (HorseListPayload.HorseEntry entry : visibleHorses) {
            AbstractHorseEntity horseEntity = getHorseEntity(entry);
            if (horseEntity != null) {
                int left = centerX - 148;
                int top = y - 20;
                int right = centerX - 46;
                int bottom = y + 38;
                InventoryScreen.drawEntity(context, left, top, right, bottom, 17, 0.0625f, mouseX, mouseY, horseEntity);

                String name = entry.name();
                if (name.length() > 18) name = name.substring(0, 16) + "...";
                context.drawTextWithShadow(this.textRenderer, name, centerX - 30, y + 12, 0xFFFFFF);
            }
            y += ROW_SPACING;
        }

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Page " + (this.currentPage + 1) + "/" + totalPages()),
                centerX,
                this.height - 78,
                0xE0E0E0);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}