package net.irisshaders.iris.gui.element;

import net.minecraft.client.gui.components.EditBox;

public interface ShaderListSearchFieldAccessor {
	boolean irisSearch$isSearchModeActive();
	void irisSearch$setSearchModeActive(boolean active);

	String irisSearch$getTypedSearchQuery();
	void irisSearch$setTypedSearchQuery(String query);

	int irisSearch$getSavedCursorPosition();
	void irisSearch$setSavedCursorPosition(int position);

	EditBox irisSearch$getActiveSearchField();
	void irisSearch$setActiveSearchField(EditBox box);

	// An abstraction to trigger container updates without hardcoding inner dependencies
	void irisSearch$triggerContainerSearchUpdate(String query);
}
