package net.irisshaders.iris.gui.element;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.List;

public class SearchInputRow extends ShaderPackOptionList.BaseEntry {

	private final EditBox editBox;
	private final ShaderPackOptionList parentList;
	private final ShaderListSearchFieldAccessor accessor;

	public SearchInputRow(Font font, ShaderPackOptionList parentList, int rowWidth, Object navigation) {
		super(parentList.getNavigation());

		this.parentList = parentList;
		this.accessor = parentList;

		// Give it standard input layout bounds matching row tracks
		this.editBox = new EditBox(font, 0, 0, rowWidth - 20, 16, Component.literal("Search options..."));
		this.editBox.setValue(accessor.irisSearch$getTypedSearchQuery());

		this.editBox.setFocused(true);
		this.setFocused(true);

		int targetCursor = Math.min(accessor.irisSearch$getSavedCursorPosition(), this.editBox.getValue().length());
		this.editBox.setCursorPosition(targetCursor);

		this.editBox.setResponder(text -> {
			accessor.irisSearch$setTypedSearchQuery(text);
			accessor.irisSearch$setSavedCursorPosition(this.editBox.getCursorPosition());
			accessor.irisSearch$triggerContainerSearchUpdate(text);
		});
	}

	public EditBox getEditBox() {
		return this.editBox;
	}

	@Override
	public void setFocused(boolean focused) {
		super.setFocused(focused);
		this.editBox.setFocused(focused);
	}

	@Override
	public boolean isFocused() {
		return this.editBox.isFocused();
	}

	@Override
	public void extractContent(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, boolean isHovered, float tickDelta) {
		int centerX = parentList.getXDimension() + (parentList.getWidthDimension() / 2);
		int boxX = centerX - (this.editBox.getWidth() / 2);

		// Vertically center the box tracking coordinates inside the row space track
		int boxY = getContentY() + 3;

		this.editBox.setX(boxX);
		this.editBox.setY(boxY);

		if (accessor.irisSearch$isSearchModeActive()) {
			this.editBox.setFocused(true);
		}

		// Draw modern 1.21.6 element state via extractWidgetRenderState
		this.editBox.extractWidgetRenderState(guiGraphics, mouseX, mouseY, tickDelta);

		if (this.editBox.getValue().isEmpty()) {
			int hintX = boxX + 4;
			int hintY = boxY + (this.editBox.getHeight() - 8) / 2;
			guiGraphics.text(Minecraft.getInstance().font, Component.literal("Search options..."), hintX, hintY, -5592406); // -5592406 is RGBA(170, 170, 170, 255)
		}
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return ImmutableList.of(this.editBox);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		this.editBox.onClick(event, doubleClick);
		return true;
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return ImmutableList.of(this.editBox);
	}
}
