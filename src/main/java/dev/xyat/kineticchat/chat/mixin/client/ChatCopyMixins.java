package dev.xyat.kineticchat.chat.mixin.client;

import dev.xyat.kineticchat.chat.client.ChatCopyCanvasScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public abstract class ChatCopyMixins extends Screen {

    @Shadow protected EditBox input;

    @Unique
    private Button kineticchat$canvasButton;

    protected ChatCopyMixins(Component title) { super(title); }

    @Inject(method = "init", at = @At("RETURN"))
    private void kineticchat$addCanvasButton(CallbackInfo ci) {
        this.kineticchat$canvasButton = Button.builder(
                        Component.translatable("gui.kineticchat.open_canvas"),
                        b -> {
                            Minecraft mc = Minecraft.getInstance();
                            // 这里更新为独立的 Accessor
                            ChatComponentAccessor accessor = (ChatComponentAccessor) mc.gui.getChat();
                            mc.setScreen(new ChatCopyCanvasScreen(accessor.getTrimmedMessages()));
                        }
                )
                .bounds(5, this.height - 30, 55, 12)
                .tooltip(Tooltip.create(Component.translatable("gui.kineticchat.open_canvas.desc")))
                .build();

        if (this.input != null) {
            this.setInitialFocus(this.input);
        }
    }

    /**
     * 手动拦截鼠标点击：
     * 由于按钮不在 children 列表中，键盘碰不到它，但我们需要在这里手动让鼠标能点中它。
     */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void kineticchat$handleManualClick(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (this.kineticchat$canvasButton != null && this.kineticchat$canvasButton.visible) {
            // 手动调用按钮的点击检测
            if (this.kineticchat$canvasButton.mouseClicked(mouseX, mouseY, button)) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void kineticchat$handleManualRelease(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        if (this.kineticchat$canvasButton != null && this.kineticchat$canvasButton.visible) {
            if (this.kineticchat$canvasButton.mouseReleased(mouseX, mouseY, button)) {
                cir.setReturnValue(true);
            }
        }
    }

    /**
     * 手动渲染按钮：
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void kineticchat$manualRender(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (this.kineticchat$canvasButton != null && this.input != null) {
            // 状态同步
            this.kineticchat$canvasButton.visible = !this.input.getValue().startsWith("/");

            if (this.kineticchat$canvasButton.visible) {
                // 手动渲染。因为不在 children 列表里，所以它永远不会被方向键选中高亮
                this.kineticchat$canvasButton.render(guiGraphics, mouseX, mouseY, partialTick);
            }
        }
    }
}
