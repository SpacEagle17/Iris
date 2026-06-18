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
	private final List<String> unusedOptions = new ArrayList<>(); // To be used when screens contain a "*" element
	private final Map<List<OptionMenuElement>, Integer> unusedOptionDumpQueue = new HashMap<>(); // Used by screens with "*" element
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
	 * Sets the active search string and dynamically filters/re-orders the mainScreen options layout.
	 */
	public void setSearchQuery(String query) {
		if (query == null || query.trim().isEmpty()) {
			this.restoreOriginalLayout();
			return;
		}

		String normalizedQuery = query.toLowerCase(java.util.Locale.ROOT).trim();

		// 1. Fetch data through our external decoupled engine
		List<OptionMenuOptionElement> allFlatOptions = ShaderSearchEngine.getAllOptionsFlattened(this.usedOptionElements);
		List<ShaderSearchEngine.ScoredOptionElement> scoredResults = new ArrayList<>();

		net.minecraft.locale.Language languageEngine = net.minecraft.locale.Language.getInstance();

		// 2. Evaluate and grade all options via isolated utility method
		for (OptionMenuOptionElement element : allFlatOptions) {
			int scoreTier = ShaderSearchEngine.computeMatchTier(element, normalizedQuery, languageEngine);
			if (scoreTier > 0) {
				scoredResults.add(new ShaderSearchEngine.ScoredOptionElement(element, scoreTier));
			}
		}

		// 3. Sort results by our strict matching priority tiers
		Collections.sort(scoredResults);

		// 4. Re-apply to active layout display
		this.applyFilteredLayout(scoredResults);
	}

	/**
	 * Unpacks processed results back into the visible Iris screen layout element map track.
	 */
	private void applyFilteredLayout(List<ShaderSearchEngine.ScoredOptionElement> sortedElements) {
		this.mainScreen.elements.clear();
		for (ShaderSearchEngine.ScoredOptionElement scored : sortedElements) {
			this.mainScreen.elements.add(scored.getElement());
		}
	}

	/**
	 * Completely rolls back layout alterations to re-establish the vanilla navigation map tracking.
	 */
	private void restoreOriginalLayout() {
		this.mainScreen.elements.clear();
		this.mainScreen.elements.addAll(this.originalMainElements);
	}
}
