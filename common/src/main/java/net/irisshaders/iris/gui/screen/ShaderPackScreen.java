package net.irisshaders.iris.gui.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gui.GuiUtil;
import net.irisshaders.iris.gui.NavigationController;
import net.irisshaders.iris.gui.OldImageButton;
import net.irisshaders.iris.gui.element.ShaderPackOptionList;
import net.irisshaders.iris.gui.element.ShaderPackSelectionList;
import net.irisshaders.iris.gui.element.screen.IrisButton;
import net.irisshaders.iris.gui.element.widget.AbstractElementWidget;
import net.irisshaders.iris.gui.element.widget.CommentedElementWidget;
import net.irisshaders.iris.mixin.GameRendererAccessor;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.transforms.SmoothedFloat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public class ShaderPackScreen extends Screen implements HudHideable {
	/**
	 * Queue rendering to happen on top of all elements. Useful for tooltips or dialogs.
	 */
	public static final Set<Runnable> TOP_LAYER_RENDER_QUEUE = new HashSet<>();

	private static final Component SELECT_TITLE = Component.translatable("pack.iris.select.title").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
	private static final Component CONFIGURE_TITLE = Component.translatable("pack.iris.configure.title").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
	private static final int COMMENT_PANEL_WIDTH = 314;
	private static final String development = "Development Environment";
	private final Screen parent;
	private final MutableComponent irisTextComponent;
	private final FrameUpdateNotifier notifier = new FrameUpdateNotifier();
	private ShaderPackSelectionList shaderPackList;
	private @Nullable ShaderPackOptionList shaderOptionList = null;
	private @Nullable NavigationController navigation = null;
	private Button screenSwitchButton;
	private Component notificationDialog = null;
	private int notificationDialogTimer = 0;
	private @Nullable AbstractElementWidget<?> hoveredElement = null;
	private Optional<Component> hoveredElementCommentTitle = Optional.empty();
	private List<FormattedCharSequence> hoveredElementCommentBody = new ArrayList<>();
	private int hoveredElementCommentTimer = 0;
	private boolean optionMenuOpen = false;
	private boolean dropChanges = false;
	private MutableComponent developmentComponent;
	private MutableComponent updateComponent;
	private boolean guiHidden = false;
	public final SmoothedFloat blurTransition = new SmoothedFloat(2, 2, () -> {
		if (guiHidden) {
			return 0.0f;
		} else if (this.optionMenuOpen) {
			return 0.1f;
		} else {
			return (float) this.minecraft.options.getMenuBackgroundBlurriness();
		}
	}, notifier);
	private float guiButtonHoverTimer = 0.0f;
	private Button openFolderButton;
	private float backgroundInit = 0.0f;
	public final SmoothedFloat listTransition = new SmoothedFloat(1, 1, () -> {
		if (guiHidden || this.optionMenuOpen) {
			return 0.0f;
		} else {
			return backgroundInit;
		}
	}, notifier);

	public final SmoothedFloat buttonTransition = new SmoothedFloat(1, 1, () -> {
		if (guiHidden) {
			return 0.0f;
		} else {
			return backgroundInit;
		}
	}, notifier);
	private OldImageButton showHideButton;

	// The search box is a normal, long-lived screen widget -- not a list entry. It is created
	// once per init() alongside shaderOptionList and merely shown/hidden/focused as search mode
	// is toggled, so typing in it goes through Minecraft's ordinary widget focus chain exactly
	// like any other vanilla EditBox.
	private @Nullable EditBox searchBox;

	public ShaderPackScreen(Screen parent) {
		super(Component.translatable("options.iris.shaderPackSelection.title"));

		this.parent = parent;

		String irisName = Iris.MODNAME + " " + Iris.getVersion();

		if (IrisPlatformHelpers.getInstance().isDevelopmentEnvironment()) {
			this.developmentComponent = Component.literal("Development Environment").withStyle(ChatFormatting.GOLD);
		}

		this.irisTextComponent = Component.literal(irisName).withStyle(ChatFormatting.GRAY);

		if (Iris.getUpdateChecker().getUpdateMessage().isPresent()) {
			this.updateComponent = Component.literal("New update available!").withStyle(ChatFormatting.GREEN).withStyle(ChatFormatting.UNDERLINE);
			irisTextComponent.append(Component.literal(" (outdated)").withStyle(ChatFormatting.RED));
		}

		refreshForChangedPack();
	}

	@Override
	protected void extractBlurredBackground(final GuiGraphicsExtractor graphics) {
		float blurRadius = Math.min(this.minecraft.options.getMenuBackgroundBlurriness(), this.blurTransition.getAsFloat());
		if (blurRadius >= 1.0F) {
			graphics.blurBeforeThisStratum();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float delta) {
		notifier.onNewFrame();
		backgroundInit = 1.0f;

		// Keep the search box's visibility/focus in sync with shaderOptionList's search state.
		// This needs to run every frame (rather than only at the few call sites that toggle
		// search mode) because HeaderEntry can also flip search mode off as a side effect of
		// being constructed for a sub-screen, and there's no single call site to hook for that.
		this.syncSearchBoxVisibility();

		if (Minecraft.getInstance().hasControlDown() && InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_D)) {
			Minecraft.getInstance().setScreen(new ConfirmScreen((option) -> {
				Iris.setDebug(option);
				Minecraft.getInstance().setScreen(this);
			}, Component.literal("Shader debug mode toggle"),
				Component.literal("Debug mode helps investigate problems and shows shader errors. Would you like to enable it?"),
				Component.literal("Yes"),
				Component.literal("No")));
		}

		if (Minecraft.getInstance().hasControlDown() && InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_G)) {
			Minecraft.getInstance().setScreen(new ConfirmScreen((option) -> {
				try {
					Iris.getIrisConfig().setUnknown(option);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				Minecraft.getInstance().setScreen(this);
			}, Component.literal("Unknown shader toggle"),
				Component.literal("This allows unknown shaders to load in."),
				Component.literal("Enable"),
				Component.literal("Disable")));
		}

		if (!this.guiHidden) {
			super.extractRenderState(guiGraphics, mouseX, mouseY, delta);
		} else {
			this.showHideButton.extractRenderState(guiGraphics, mouseX, mouseY, delta);
		}

		float previousHoverTimer = this.guiButtonHoverTimer;
		if (previousHoverTimer == this.guiButtonHoverTimer) {
			this.guiButtonHoverTimer = 0.0f;
		}

		if (!this.guiHidden) {
			guiGraphics.centeredText(this.font, this.title, (int) (this.width * 0.5), 8, 0xFFFFFFFF);

			if (notificationDialog != null && notificationDialogTimer > 0) {
				guiGraphics.centeredText(this.font, notificationDialog, (int) (this.width * 0.5), 21, 0xFFFFFFFF);
			} else {
				if (optionMenuOpen) {
					guiGraphics.centeredText(this.font, CONFIGURE_TITLE, (int) (this.width * 0.5), 21, 0xFFFFFFFF);
				} else {
					guiGraphics.centeredText(this.font, SELECT_TITLE, (int) (this.width * 0.5), 21, 0xFFFFFFFF);
				}
			}

			// Draw the comment panel
			if (this.isDisplayingComment()) {
				// Determine panel height and position
				int panelHeight = Math.max(50, 18 + (this.hoveredElementCommentBody.size() * 10));
				int x = (int) (0.5 * this.width) - 157;
				int y = this.height - (panelHeight + 4);
				// Draw panel
				GuiUtil.drawPanel(guiGraphics, x, y, COMMENT_PANEL_WIDTH, panelHeight);
				// Draw text
				guiGraphics.text(font, this.hoveredElementCommentTitle.orElse(Component.empty()), x + 4, y + 4, 0xFFFFFFFF);
				for (int i = 0; i < this.hoveredElementCommentBody.size(); i++) {
					guiGraphics.text(font, this.hoveredElementCommentBody.get(i), x + 4, (y + 16) + (i * 10), 0xFFFFFFFF);
				}
			}
		}

		// Render everything queued to render last
		for (Runnable render : TOP_LAYER_RENDER_QUEUE) {
			render.run();
		}
		TOP_LAYER_RENDER_QUEUE.clear();

		if (this.developmentComponent != null) {
			guiGraphics.text(font, developmentComponent, 2, this.height - 10, 0xFFFFFFFF);
			guiGraphics.text(font, irisTextComponent, 2, this.height - 20, 0xFFFFFFFF);
		} else if (this.updateComponent != null) {
			guiGraphics.text(font, updateComponent, 2, this.height - 10, 0xFFFFFFFF);
			guiGraphics.text(font, irisTextComponent, 2, this.height - 20, 0xFFFFFFFF);
		} else {
			guiGraphics.text(font, irisTextComponent, 2, this.height - 10, 0xFFFFFFFF);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean bl2) {

		if (this.optionMenuOpen && this.searchBox != null && this.searchBox.isVisible()) {
			if (this.searchBox.mouseClicked(event, bl2)) {
				this.focusSearchBox(this.searchBox);
				return true;
			}
		}

		int widthValue = this.font.width("New update available!");
		double x = event.x();
		double y = event.y();
		if (this.updateComponent != null && x < widthValue && y > (this.height - 10) && y < this.height) {
			this.minecraft.setScreen(new ConfirmLinkScreen(bl -> {
				if (bl) {
					Iris.getUpdateChecker().getUpdateLink().ifPresent(Util.getPlatform()::openUri);
				}
				this.minecraft.setScreen(this);
			}, Iris.getUpdateChecker().getUpdateLink().map(URI::toString).orElse(""), true));
		}
		return super.mouseClicked(event, bl2);
	}

	@Override
	protected void init() {
		super.init();
		int bottomCenter = this.width / 2 - 50;
		int topCenter = this.width / 2 - 76;
		boolean inWorld = this.minecraft.level != null;

		this.removeWidget(this.shaderPackList);
		this.removeWidget(this.shaderOptionList);
		this.removeWidget(this.searchBox);

		this.shaderPackList = new ShaderPackSelectionList(this, this.minecraft, this.width, this.height, 32, this.height - 58 - 36, 0, this.width);

		if (Iris.getCurrentPack().isPresent() && this.navigation != null) {
			ShaderPack currentPack = Iris.getCurrentPack().get();

			// Reset query on new UI load
			if (currentPack.getMenuContainer() != null) {
				currentPack.getMenuContainer().setSearchQuery(null);
			}

			this.shaderOptionList = new ShaderPackOptionList(this, this.navigation, currentPack, this.minecraft, this.width, this.height, 32, this.height - 58 - 36, 0, this.width);
			this.navigation.setActiveOptionList(this.shaderOptionList);

			this.shaderOptionList.rebuild();
		} else {
			optionMenuOpen = false;
			this.shaderOptionList = null;
		}

		this.clearWidgets();
		this.searchBox = null;

		if (!this.guiHidden) {
			if (optionMenuOpen && shaderOptionList != null) {
				this.addRenderableWidget(shaderOptionList);

				this.searchBox = createSearchBox();
				if (this.searchBox != null) {
					this.addRenderableWidget(this.searchBox);
				}
			} else {
				this.addRenderableWidget(shaderPackList);
			}

			this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_DONE, button -> onClose(), buttonTransition).bounds(bottomCenter + 104, this.height - 27, 100, 20
			).build());

			this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.apply"), button -> this.applyChanges(), buttonTransition).bounds(bottomCenter, this.height - 27, 100, 20
			).build());

			this.addRenderableWidget(IrisButton.iris$builder(CommonComponents.GUI_CANCEL, button -> this.dropChangesAndClose(), buttonTransition).bounds(bottomCenter - 104, this.height - 27, 100, 20
			).build());

			this.openFolderButton = IrisButton.iris$builder(Component.translatable("options.iris.openShaderPackFolder"), button -> openShaderPackFolder(), buttonTransition).bounds(topCenter - 78, this.height - 51, 152, 20
			).build();
			this.addRenderableWidget(openFolderButton);

			this.screenSwitchButton = this.addRenderableWidget(IrisButton.iris$builder(Component.translatable("options.iris.shaderPackList"), button -> {
					this.optionMenuOpen = !this.optionMenuOpen;

					// UX: Apply changes before switching screens to avoid unintuitive behavior
					//
					// Not doing this leads to unintuitive behavior, since selecting a pack in the
					// list (but not applying) would open the settings for the previous pack, rather
					// than opening the settings for the selected (but not applied) pack.
					this.applyChanges();
					setFocused(shaderPackList.getFocused());
					this.init();
				}
				, buttonTransition).bounds(topCenter + 78, this.height - 51, 152, 20
			).build());

			refreshScreenSwitchButton();
		}

		if (inWorld) {
			Component showOrHide = this.guiHidden
				? Component.translatable("options.iris.gui.show")
				: Component.translatable("options.iris.gui.hide");

			float endOfLastButton = this.width / 2.0f + 154.0f;
			float freeSpace = this.width - endOfLastButton;
			int x;
			if (freeSpace > 100.0f) {
				x = this.width - 50;
			} else if (freeSpace < 20.0f) {
				x = this.width - 20;
			} else {
				x = (int) (endOfLastButton + (freeSpace / 2.0f)) - 10;
			}

			this.showHideButton = new OldImageButton(
				x, this.height - 39,
				20, 20,
				this.guiHidden ? 20 : 0, 146, 20,
				GuiUtil.IRIS_WIDGETS_TEX,
				256, 256,
				(button) -> {
					this.guiHidden = !this.guiHidden;
					this.init();
				},
				showOrHide
			);

			showHideButton.setTooltip(Tooltip.create(showOrHide));
			showHideButton.setTooltipDelay(Duration.ofSeconds(10));

			this.addRenderableWidget(showHideButton);
		}

		// NB: Don't let comment remain when exiting options screen
		// https://github.com/IrisShaders/Iris/issues/1494
		this.hoveredElement = null;
		this.hoveredElementCommentTimer = 0;
	}

	/**
	 * Builds the persistent search EditBox, seeded from shaderOptionList's currently saved
	 * query/cursor (which will just be the defaults on a fresh ShaderPackOptionList, since that
	 * object is itself recreated every init() -- this matches how search state already didn't
	 * survive a full init() before this change, so there's no new loss of behavior here).
	 *
	 * This box is a pure overlay: it takes no list space when hidden, and when shown it is
	 * drawn on top of the option list's header row, stopping short of the search/clear button
	 * on the right so that button stays visible and clickable (Escape also always works as a
	 * fallback way out of search mode regardless).
	 */
	private @Nullable EditBox createSearchBox() {
		if (this.shaderOptionList == null) {
			return null;
		}

		EditBox box = new EditBox(this.font, 0, 0, 10, 16, Component.literal("Search shader options"));
		box.setMaxLength(64);
		box.setBordered(true);
		box.setHint(Component.literal("Search options...").withStyle(EditBox.SEARCH_HINT_STYLE));
		positionSearchBox(box);

		String savedQuery = this.shaderOptionList.getTypedSearchQuery();
		box.setValue(savedQuery);
		box.setCursorPosition(Math.min(this.shaderOptionList.getSavedCursorPosition(), savedQuery.length()));

		box.setResponder(text -> {
			if (this.shaderOptionList == null) {
				return;
			}

			this.shaderOptionList.setTypedSearchQuery(text);
			this.shaderOptionList.setSavedCursorPosition(box.getCursorPosition());
			this.shaderOptionList.updateSearchQuery(text);
		});

		box.setVisible(this.shaderOptionList.isSearchModeActive());

		if (box.isVisible()) {
			focusSearchBox(box);
		}

		return box;
	}

	/**
	 * Lays the search box directly over the option list's header row (same row the back/clear
	 * button lives in), reserving a margin on the right so it never covers that button.
	 */
	private void positionSearchBox(EditBox box) {
		if (this.shaderOptionList == null) {
			return;
		}

		final int headerRowHeight = 24; // matches fixed item height
		final int boxHeight = 16;

		// Calculate the actual centered row bounds instead of using the full screen width
		int rowWidth = this.shaderOptionList.getRowWidth();
		int rowX = this.shaderOptionList.getX() + (this.shaderOptionList.getWidth() - rowWidth) / 2;
		int listY = this.shaderOptionList.getY();

		// Left margin clears the Back/Search/Clear slot; Right extends completely to the edge
		final int leftMargin = 48;
		final int rightMargin = 4;

		int boxX = rowX + leftMargin;
		int boxY = listY + ((headerRowHeight - boxHeight) / 2) - 2;
		int boxWidth = Math.max(40, rowWidth - leftMargin - rightMargin);

		box.setX(boxX);
		box.setY(boxY);
		box.setWidth(boxWidth);
		box.setHeight(boxHeight);
	}

	/**
	 * Properly focuses the search box: sets both the widget's own focus flag (used for its
	 * cursor-blink rendering) AND tells the screen that this widget is the focused child, since
	 * those are two separate things in Minecraft's GUI framework. Calling only setFocused(true)
	 * on the widget -- without also updating the screen's own focused-child pointer -- leaves
	 * keyboard input still routed wherever it was before, which is why typing wouldn't work
	 * until the box was clicked manually.
	 */
	private void focusSearchBox(EditBox box) {
		box.setFocused(true);
		this.setFocused(box);
	}

	private void unfocusSearchBox(EditBox box) {
		box.setFocused(false);

		if (this.getFocused() == box) {
			this.setFocused(null);
		}
	}

	/**
	 * Single sync point keeping the persistent search box's visibility/focus aligned with
	 * shaderOptionList's search-mode flag, regardless of which code path changed that flag
	 * (the header's search/clear button, the Escape key handler, onClose, or HeaderEntry
	 * force-disabling search when a sub-screen opens).
	 */
	private void syncSearchBoxVisibility() {
		if (this.searchBox == null || this.shaderOptionList == null) {
			return;
		}

		boolean shouldBeActive = this.optionMenuOpen && this.shaderOptionList.isSearchModeActive();
		if (shouldBeActive == this.searchBox.isVisible()) {
			return;
		}

		if (shouldBeActive) {
			// Becoming active: (re)seed the box from whatever query/cursor is currently saved,
			// re-align it against the list's current bounds, then properly focus it.
			String query = this.shaderOptionList.getTypedSearchQuery();
			this.searchBox.setValue(query);
			this.searchBox.setCursorPosition(Math.min(this.shaderOptionList.getSavedCursorPosition(), query.length()));
			positionSearchBox(this.searchBox);

			this.searchBox.setVisible(true);
			focusSearchBox(this.searchBox);
		} else {
			this.searchBox.setVisible(false);
			unfocusSearchBox(this.searchBox);
		}
	}

	public void refreshForChangedPack() {
		if (Iris.getCurrentPack().isPresent()) {
			ShaderPack currentPack = Iris.getCurrentPack().get();

			this.navigation = new NavigationController(currentPack.getMenuContainer());

			if (this.shaderOptionList != null) {
				this.shaderOptionList.applyShaderPack(currentPack);
				this.shaderOptionList.rebuild();
			}
		} else {
			this.navigation = null;
		}

		refreshScreenSwitchButton();
	}

	public void refreshScreenSwitchButton() {
		if (this.screenSwitchButton != null) {
			this.screenSwitchButton.setMessage(
				optionMenuOpen ?
					Component.translatable("options.iris.shaderPackList")
					: Component.translatable("options.iris.shaderPackSettings")
			);
			this.screenSwitchButton.active = optionMenuOpen || (shaderPackList.getTopButtonRow().shadersEnabled && Iris.getCurrentPack().map(p -> !p.getMenuContainer().mainScreen.elements.isEmpty()).orElse(true));
		}
	}
	private static final Identifier BLUR_POST_CHAIN_ID = Identifier.withDefaultNamespace("blur");

	@Override
	public void tick() {
		super.tick();

		if (this.notificationDialogTimer > 0) {
			this.notificationDialogTimer--;
		}

		if (this.hoveredElement != null) {
			this.hoveredElementCommentTimer++;
		} else {
			this.hoveredElementCommentTimer = 0;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {

		if (this.shaderOptionList != null) {
			// If the options list exists and search mode is active, hijack the escape key!
			if (event.isEscape() && this.shaderOptionList.isSearchModeActive()) {
				this.shaderOptionList.disableSearchModeAndRebuild();
				return true;
			}

			if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_F) {
				if (this.optionMenuOpen) {
					GuiUtil.playButtonClickSound();
					if (this.shaderOptionList.isSearchModeActive()) {
						this.shaderOptionList.disableSearchModeAndRebuild();
					} else {
						this.shaderOptionList.enableSearchModeAndRebuild();
					}
					return true;
				}
			}
		}


		if (event.isEscape()) {

			if (this.guiHidden) {
				this.guiHidden = false;
				this.init();

				return true;
			} else if (this.navigation != null && this.navigation.hasHistory()) {
				this.navigation.back();

				return true;
			} else if (this.optionMenuOpen) {
				this.optionMenuOpen = false;
				this.init();

				return true;
			}
		} else if (event.isCycleFocus()) {
			if (!optionMenuOpen) {
				shaderPackList.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
			}

			this.optionMenuOpen = !this.optionMenuOpen;

			// UX: Apply changes before switching screens to avoid unintuitive behavior
			//
			// Not doing this leads to unintuitive behavior, since selecting a pack in the
			// list (but not applying) would open the settings for the previous pack, rather
			// than opening the settings for the selected (but not applied) pack.
			this.applyChanges();

			this.init();

			this.setFocused(null);
		} else if (event.key() == GLFW.GLFW_KEY_F1 && this.showHideButton != null) {
			this.guiHidden = !guiHidden;
			this.init();
		}

		return this.guiHidden || super.keyPressed(event);
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		if (this.optionMenuOpen) {
			onOptionMenuFilesDrop(paths);
		} else {
			onPackListFilesDrop(paths);
		}
	}

	public void onPackListFilesDrop(List<Path> paths) {
		List<Path> packs = paths.stream().filter(Iris::isValidShaderpack).toList();

		for (Path pack : packs) {
			String fileName = pack.getFileName().toString();

			try {
				Iris.getShaderpacksDirectoryManager().copyPackIntoDirectory(fileName, pack);
			} catch (FileAlreadyExistsException e) {
				this.notificationDialog = Component.translatable(
					"options.iris.shaderPackSelection.copyErrorAlreadyExists",
					fileName
				).withStyle(ChatFormatting.ITALIC, ChatFormatting.RED);

				this.notificationDialogTimer = 100;
				this.shaderPackList.refresh();

				return;
			} catch (IOException e) {
				Iris.logger.warn("Error copying dragged shader pack", e);

				this.notificationDialog = Component.translatable(
					"options.iris.shaderPackSelection.copyError",
					fileName
				).withStyle(ChatFormatting.ITALIC, ChatFormatting.RED);

				this.notificationDialogTimer = 100;
				this.shaderPackList.refresh();

				return;
			}
		}

		// After copying the relevant files over to the folder, make sure to refresh the shader pack list.
		this.shaderPackList.refresh();

		if (packs.isEmpty()) {
			// If zero packs were added, then notify the user that the files that they added weren't actually shader
			// packs.

			if (paths.size() == 1) {
				// If a single pack could not be added, provide a message with that pack in the file name
				String fileName = paths.getFirst().getFileName().toString();

				this.notificationDialog = Component.translatable(
					"options.iris.shaderPackSelection.failedAddSingle",
					fileName
				).withStyle(ChatFormatting.ITALIC, ChatFormatting.RED);
			} else {
				// Otherwise, show a generic message.

				this.notificationDialog = Component.translatable(
					"options.iris.shaderPackSelection.failedAdd"
				).withStyle(ChatFormatting.ITALIC, ChatFormatting.RED);
			}

		} else if (packs.size() == 1) {
			// In most cases, users will drag a single pack into the selection menu. So, let's special case it.
			String packName = packs.getFirst().getFileName().toString();

			this.notificationDialog = Component.translatable(
				"options.iris.shaderPackSelection.addedPack",
				packName
			).withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW);

			// Select the pack that the user just added, since if a user just dragged a pack in, they'll probably want
			// to actually use that pack afterwards.
			this.shaderPackList.select(packName);
		} else {
			// We also support multiple packs being dragged and dropped at a time. Just show a generic success message
			// in that case.
			this.notificationDialog = Component.translatable(
				"options.iris.shaderPackSelection.addedPacks",
				packs.size()
			).withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW);
		}

		// Show the relevant message for 5 seconds (100 ticks)
		this.notificationDialogTimer = 100;
	}

	public void displayNotification(Component component) {
		this.notificationDialog = component;
		this.notificationDialogTimer = 100;
	}

	public void onOptionMenuFilesDrop(List<Path> paths) {
		// If more than one option file has been dragged, display an error
		// as only one option file should be imported at a time
		if (paths.size() != 1) {
			this.notificationDialog = Component.translatable(
				"options.iris.shaderPackOptions.tooManyFiles"
			).withStyle(ChatFormatting.ITALIC, ChatFormatting.RED);
			this.notificationDialogTimer = 100; // 5 seconds (100 ticks)

			return;
		}

		this.importPackOptions(paths.getFirst());
	}

	public void importPackOptions(Path settingFile) {
		try (InputStream in = Files.newInputStream(settingFile)) {
			Properties properties = new Properties();
			properties.load(in);

			Iris.queueShaderPackOptionsFromProperties(properties);

			this.notificationDialog = Component.translatable(
				"options.iris.shaderPackOptions.importedSettings",
				settingFile.getFileName().toString()
			).withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW);
			this.notificationDialogTimer = 100; // 5 seconds (100 ticks)

			if (this.navigation != null) {
				this.navigation.refresh();
			}
		} catch (Exception e) {
			// If the file could not be properly parsed or loaded,
			// log the error and display a message to the user
			Iris.logger.error("Error importing shader settings file \"" + settingFile.toString() + "\"", e);

			this.notificationDialog = Component.translatable(
				"options.iris.shaderPackOptions.failedImport",
				settingFile.getFileName().toString()
			).withStyle(ChatFormatting.ITALIC, ChatFormatting.RED);
			this.notificationDialogTimer = 100; // 5 seconds (100 ticks)
		}
	}

	@Override
	public void onClose() {

		if (this.shaderOptionList != null) {
			this.shaderOptionList.disableSearchModeAndRebuild();
		}

		if (!dropChanges) {
			applyChanges();
		} else {
			discardChanges();
		}

		try {
			shaderPackList.close();
		} catch (IOException e) {
			Iris.logger.error("Failed to safely close shaderpack selection!", e);
		}

		this.minecraft.setScreen(parent);
	}

	private void dropChangesAndClose() {
		dropChanges = true;
		onClose();
	}

	public void applyChanges() {
		ShaderPackSelectionList.BaseEntry base = this.shaderPackList.getSelected();
		boolean enabled = this.shaderPackList.getTopButtonRow().shadersEnabled;
		boolean previousShadersEnabled = Iris.getIrisConfig().areShadersEnabled();

		if (enabled != previousShadersEnabled) {
			IrisApi.getInstance().getConfig().setShadersEnabledAndApply(enabled);
		}

		if (!(base instanceof ShaderPackSelectionList.ShaderPackEntry entry)) {
			return;
		}

		this.shaderPackList.setApplied(entry);

		String name = entry.getPackName();

		// If the pack is being changed, clear pending options from the previous pack to
		// avoid possible undefined behavior from applying one pack's options to another pack
		if (!name.equals(Iris.getCurrentPackName())) {
			Iris.clearShaderPackOptionQueue();
		}

		String previousPackName = Iris.getIrisConfig().getShaderPackName().orElse(null);

		// Only reload if the pack would be different from before, or shaders were toggled, or options were changed, or if we're about to reset options.
		if (!name.equals(previousPackName) || !Iris.getShaderPackOptionQueue().isEmpty() || Iris.shouldResetShaderPackOptionsOnNextReload()) {
			Iris.getIrisConfig().setShaderPackName(name);
			IrisApi.getInstance().getConfig().setShadersEnabledAndApply(enabled);
		}

		refreshForChangedPack();
	}

	private void discardChanges() {
		Iris.clearShaderPackOptionQueue();
	}

	private void openShaderPackFolder() {
		CompletableFuture.runAsync(() -> Util.getPlatform().openUri(Iris.getShaderpacksDirectoryManager().getDirectoryUri()));
	}

	// Let the screen know if an element is hovered or not, allowing for accurately updating which element is hovered
	public void setElementHoveredStatus(AbstractElementWidget<?> widget, boolean hovered) {
		if (hovered && widget != this.hoveredElement) {
			this.hoveredElement = widget;

			if (widget instanceof CommentedElementWidget) {
				this.hoveredElementCommentTitle = ((CommentedElementWidget<?>) widget).getCommentTitle();

				Optional<Component> commentBody = ((CommentedElementWidget<?>) widget).getCommentBody();
				if (commentBody.isEmpty()) {
					this.hoveredElementCommentBody.clear();
				} else {
					String rawCommentBody = commentBody.get().getString();

					// Strip any trailing "."s
					if (rawCommentBody.endsWith(".")) {
						rawCommentBody = rawCommentBody.substring(0, rawCommentBody.length() - 1);
					}
					// Split comment body into lines by separator ". "
					List<MutableComponent> splitByPeriods = Arrays.stream(rawCommentBody.split("\\. [ ]*")).map(Component::literal).toList();
					// Line wrap
					this.hoveredElementCommentBody = new ArrayList<>();
					for (MutableComponent text : splitByPeriods) {
						this.hoveredElementCommentBody.addAll(this.font.split(text, COMMENT_PANEL_WIDTH - 8));
					}
				}
			} else {
				this.hoveredElementCommentTitle = Optional.empty();
				this.hoveredElementCommentBody.clear();
			}

			this.hoveredElementCommentTimer = 0;
		} else if (!hovered && widget == this.hoveredElement) {
			this.hoveredElement = null;
			this.hoveredElementCommentTitle = Optional.empty();
			this.hoveredElementCommentBody.clear();
			this.hoveredElementCommentTimer = 0;
		}
	}

	public boolean isDisplayingComment() {
		return this.hoveredElementCommentTimer > 10 &&
			this.hoveredElementCommentTitle.isPresent() &&
			!this.hoveredElementCommentBody.isEmpty();
	}

	public Button getBottomRowOption() {
		return openFolderButton;
	}
}
