package net.irisshaders.iris.shaderpack.option.menu;

import com.google.common.collect.Lists;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.option.ProfileSet;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class OptionMenuContainer {
	public final OptionMenuElementScreen mainScreen;
	public final Map<String, OptionMenuElementScreen> subScreens = new HashMap<>();

	private final List<OptionMenuOptionElement> usedOptionElements = new ArrayList<>();
	private final List<String> usedOptions = new ArrayList<>();
	private final List<String> unusedOptions = new ArrayList<>();
	private final Map<List<OptionMenuElement>, Integer> unusedOptionDumpQueue = new HashMap<>();
	private final ProfileSet profiles;

	private final List<OptionMenuElement> originalMainElements = new ArrayList<>();

	public OptionMenuContainer(ShaderProperties shaderProperties, ShaderPackOptions shaderPackOptions, ProfileSet profiles) {
		this.profiles = profiles;

		// note: if the Shader Pack does not provide a list of options for the main screen, then dump all options on to
		// the main screen by default.
		this.mainScreen = new OptionMenuMainElementScreen(
			this, shaderProperties, shaderPackOptions,
			shaderProperties.getMainScreenOptions().orElseGet(() -> Collections.singletonList("*")),
			shaderProperties.getMainScreenColumnCount());

		this.unusedOptions.addAll(shaderPackOptions.getOptionSet().getBooleanOptions().keySet());
		this.unusedOptions.addAll(shaderPackOptions.getOptionSet().getStringOptions().keySet());

		Map<String, Integer> subScreenColumnCounts = shaderProperties.getSubScreenColumnCount();
		shaderProperties.getSubScreenOptions().forEach((screenKey, options) -> subScreens.put(screenKey, new OptionMenuSubElementScreen(
			screenKey, this, shaderProperties, shaderPackOptions, options, Optional.ofNullable(subScreenColumnCounts.get(screenKey)))));

		// Dump all unused options into screens containing "*"
		for (Map.Entry<List<OptionMenuElement>, Integer> entry : unusedOptionDumpQueue.entrySet()) {
			List<OptionMenuElement> elementsToInsert = new ArrayList<>();
			List<String> unusedOptionsCopy = Lists.newArrayList(this.unusedOptions);

			for (String optionId : unusedOptionsCopy) {
				try {
					OptionMenuElement element = OptionMenuElement.create(optionId, this, shaderProperties, shaderPackOptions);
					if (element != null) {
						elementsToInsert.add(element);

						if (element instanceof OptionMenuOptionElement) {
							this.notifyOptionAdded(optionId, (OptionMenuOptionElement) element);
						}
					}
				} catch (IllegalArgumentException error) {
					Iris.logger.warn(error);

					elementsToInsert.add(OptionMenuElement.EMPTY);
				}
			}

			entry.getKey().addAll(entry.getValue(), elementsToInsert);
		}

		// Capture the original layout elements right after they're finished initializing
		this.originalMainElements.addAll(this.mainScreen.elements);
	}

	public ProfileSet getProfiles() {
		return profiles;
	}

	// Screens will call this when they contain a "*" element, so that the list of
	// unused options can be added after all other screens have been resolved
	public void queueForUnusedOptionDump(int index, List<OptionMenuElement> elementList) {
		this.unusedOptionDumpQueue.put(elementList, index);
	}

	public void notifyOptionAdded(String optionId, OptionMenuOptionElement option) {
		if (!usedOptions.contains(optionId)) {
			usedOptionElements.add(option);
			usedOptions.add(optionId);
		}

		unusedOptions.remove(optionId);
	}


	/**
	 * Sets the active search string and dynamically rewrites the mainScreen elements list.
	 * Pass null or an empty string to restore the original layout.
	 */
	public void setSearchQuery(String query) {
		String currentSearchQuery ;
		if (query == null || query.trim().isEmpty()) {
			this.mainScreen.elements.clear();
			this.mainScreen.elements.addAll(this.originalMainElements);
			return;
		}

		currentSearchQuery = query.toLowerCase(java.util.Locale.ROOT);
		List<OptionMenuOptionElement> allFlatOptions = this.getAllOptionsFlattened();
		List<OptionMenuElement> filteredResults = new ArrayList<>();

		for (OptionMenuOptionElement element : allFlatOptions) {
			String idPart = element.optionId != null ? element.optionId.toLowerCase(java.util.Locale.ROOT) : "";
			String readableName = getReadableNameOfElement(element);
			String namePart = readableName != null ? readableName.toLowerCase(java.util.Locale.ROOT) : "";

			String matchTarget = idPart + " " + namePart;

			if (matchTarget.contains(currentSearchQuery)) {
				filteredResults.add(element);
			}
		}

		this.mainScreen.elements.clear();
		this.mainScreen.elements.addAll(filteredResults);
	}

	public List<OptionMenuOptionElement> getAllOptionsFlattened() {
		List<OptionMenuOptionElement> flatList = new ArrayList<>();
		List<String> seenOptionIds = new ArrayList<>();

		for (OptionMenuOptionElement element : this.usedOptionElements) {
			if (element == null) continue;
			String id = element.optionId != null ? element.optionId : element.toString();

			if (!seenOptionIds.contains(id)) {
				seenOptionIds.add(id);
				flatList.add(element);
			}
		}
		return flatList;
	}

	/**
	 * Resolves the localized user-facing name using safe translation methods
	 * that do not trigger formatting string parsing exceptions.
	 */
	private String getReadableNameOfElement(OptionMenuOptionElement element) {
		if (element == null || element.optionId == null) {
			return null;
		}

		String optionId = element.optionId;
		String translationKey = "option." + optionId;
		net.minecraft.locale.Language languageEngine = net.minecraft.locale.Language.getInstance();

		if (languageEngine.has(translationKey)) {
			return languageEngine.getOrDefault(translationKey);
		}

		return null;
	}
}
