package dev.cyberblock.client.net;

import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * The join password box: the server sends {@code cyberblock:ui} {@code login} to a locked player (again, with {@code left}, after a
 * wrong password) and this screen sends {@code /login <password>} for them. The text is shown as stars and never goes into the
 * chat history. Esc closes it; typing {@code /login <password>} in chat works the same.
 */
public final class LoginScreen extends Screen {
	private static final int WIDTH = 200;
	/** The server asked for the box: it opens once the player is in the world with no other screen (client thread). */
	private static boolean pending;
	private static int left = -1;
	private final int attemptsLeft;
	private EditBox box;

	private LoginScreen(final int attemptsLeft) {
		super(Component.translatableWithFallback("cyberblock.login.title", "Password needed"));
		this.attemptsLeft = attemptsLeft;
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			if (pending && minecraft.player != null && minecraft.gui.screen() == null) {
				pending = false;
				minecraft.gui.setScreen(new LoginScreen(left));
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> minecraft.execute(() -> pending = false));
	}

	/**
	 * A {@code cyberblock:ui} message (client thread): true when the client net took it, the {@code login} one here or a type
	 * listed in {@link UiRoutes} (the shared map's {@code maptile}, ...); false leaves it to the cyber track.
	 */
	public static boolean onUi(final String t, final String json) {
		if (!t.equals("login")) {
			return UiRoutes.dispatch(t, json);
		}

		try {
			var m = JsonParser.parseString(json).getAsJsonObject();
			left = m.has("left") ? m.get("left").getAsInt() : -1;
		} catch (RuntimeException e) {
			left = -1;
		}

		pending = true;
		return true;
	}

	@Override
	protected void init() {
		int x = this.width / 2 - WIDTH / 2;
		int y = this.height / 2 - 20;
		this.box = new EditBox(this.font, x, y, WIDTH, 20, Component.translatableWithFallback("cyberblock.login.box", "Password"));
		this.box.setMaxLength(64);
		this.box.addFormatter((text, start) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
		this.addRenderableWidget(this.box);
		this.setInitialFocus(this.box);
		this.addRenderableWidget(Button.builder(Component.translatableWithFallback("cyberblock.login.button", "Log in"), b -> this.submit())
			.bounds(x, y + 28, WIDTH / 2 - 2, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> this.onClose()).bounds(x + WIDTH / 2 + 2, y + 28, WIDTH / 2 - 2, 20).build());
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (event.isConfirmation()) {
			this.submit();
			return true;
		}

		return super.keyPressed(event);
	}

	private void submit() {
		String password = this.box.getValue().strip();
		Minecraft minecraft = Minecraft.getInstance();
		if (password.isEmpty() || minecraft.player == null) {
			return;
		}

		minecraft.player.connection.sendCommand("login " + password);
		this.onClose();
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		int y = this.height / 2 - 20;
		graphics.centeredText(this.font, this.title, this.width / 2, y - 36, 0xFFFFFFFF);
		Component line = this.attemptsLeft >= 0
			? Component.translatableWithFallback("cyberblock.login.wrong", "Wrong password (attempts left: %s)", this.attemptsLeft)
			: Component.translatableWithFallback("cyberblock.login.prompt", "This server needs a password: type /login <password>");
		graphics.centeredText(this.font, line, this.width / 2, y - 18, this.attemptsLeft >= 0 ? 0xFFFF5555 : 0xFFAAAAAA);
	}
}
