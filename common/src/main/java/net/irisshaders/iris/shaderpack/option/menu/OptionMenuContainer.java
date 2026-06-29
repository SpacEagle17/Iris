package net.irisshaders.iris.shaderpack.option.menu;

import com.google.common.collect.Lists;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.option.ProfileSet;
import net.irisshaders.iris.shaderpack.option.ShaderPackOptions;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class OptionMenuContainer {
	public final OptionMenuElementScreen mainScreen;
	public final Map<String, OptionMenuElementScreen> subScreens = new HashMap<>();

	private final List<OptionMenuOptionElement> usedOptionElements = new ArrayList<>();
	private final List<String> usedOptions = new ArrayList<>();
	private final List<String> unusedOptions = new ArrayList<>();
	private final Map<List<OptionMenuElement>, Integer> unusedOptionDumpQueue = new HashMap<>();
	private final ProfileSet profiles;

	private final List<OptionMenuElement> originalMainElements = new ArrayList<>();

	/** Full "root/SCREEN1/SCREEN2" path for every option ID, built once after construction. */
	private final Map<String, String> cachedOptionPaths = new HashMap<>();

	public OptionMenuContainer(ShaderProperties shaderProperties, ShaderPackOptions shaderPackOptions, ProfileSet profiles) {
		this.profiles = profiles;

		this.mainScreen = new OptionMenuMainElementScreen(
			this, shaderProperties, shaderPackOptions,
			shaderProperties.getMainScreenOptions().orElseGet(() -> Collections.singletonList("*")),
			shaderProperties.getMainScreenColumnCount());

		this.unusedOptions.addAll(shaderPackOptions.getOptionSet().getBooleanOptions().keySet());
		this.unusedOptions.addAll(shaderPackOptions.getOptionSet().getStringOptions().keySet());

		Map<String, Integer> subScreenColumnCounts = shaderProperties.getSubScreenColumnCount();
		shaderProperties.getSubScreenOptions().forEach((screenKey, options) -> subScreens.put(screenKey, new OptionMenuSubElementScreen(
			screenKey, this, shaderProperties, shaderPackOptions, options, Optional.ofNullable(subScreenColumnCounts.get(screenKey)))));

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

		this.originalMainElements.addAll(this.mainScreen.elements);

		// Build the option → path cache after the full tree is constructed.
		generateAllPaths();
	}

	public ProfileSet getProfiles() {
		return profiles;
	}

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

	// --- Option path cache ---

	private void generateAllPaths() {
		cachedOptionPaths.clear();
		traverseScreen(this.mainScreen, "root", new HashSet<>());
	}

	private void traverseScreen(OptionMenuElementScreen screen, String currentPath, Set<String> visited) {
		if (screen == null || screen.elements == null) return;
		for (OptionMenuElement element : screen.elements) {
			if (element == null) continue;
			if (element instanceof OptionMenuOptionElement optEl && optEl.optionId != null) {
				cachedOptionPaths.putIfAbsent(optEl.optionId, currentPath);
			} else if (element instanceof OptionMenuLinkElement link && link.targetScreenId != null) {
				String targetId = link.targetScreenId;
				if (visited.add(targetId)) {
					OptionMenuElementScreen next = this.subScreens.get(targetId);
					if (next != null) {
						traverseScreen(next, currentPath + "/" + targetId, visited);
					}
					visited.remove(targetId);
				}
			}
		}
	}

	/**
	 * Returns the cached GUI path for an option (e.g. {@code "root"} or
	 * {@code "root/LIGHTING/SHADOWS"}). Never returns null.
	 */
	public String getOptionPath(String optionId) {
		if (optionId == null) return "root";
		return cachedOptionPaths.getOrDefault(optionId, "root");
	}

	// --- Search ---

	/**
	 * Filters and re-orders the main-screen options to match {@code query}, or restores the
	 * original layout when {@code query} is null or blank.
	 */
	public void setSearchQuery(String query) {
		if (query == null || query.trim().isEmpty()) {
			restoreOriginalLayout();
			return;
		}

		String normalizedQuery = query.toLowerCase(Locale.ROOT).trim();

		List<OptionMenuOptionElement> flatOptions = ShaderSearchEngine.getAllOptionsFlattened(usedOptionElements);
		List<ShaderSearchEngine.ScoredOptionElement> scored = new ArrayList<>();

		for (OptionMenuOptionElement el : flatOptions) {
			int score = ShaderSearchEngine.computeMatchTier(el.optionId, normalizedQuery);
			if (score > 0) {
				scored.add(new ShaderSearchEngine.ScoredOptionElement(
					el,
					ShaderSearchEngine.getReadableTranslatedName(el.optionId),
					ShaderSearchEngine.getReadableDefaultName(el.optionId),
					getOptionPath(el.optionId),
					score,
					normalizedQuery
				));
			}
		}

		Collections.sort(scored);
		applyFilteredLayout(scored);
	}

	private void applyFilteredLayout(List<ShaderSearchEngine.ScoredOptionElement> sortedElements) {
		this.mainScreen.elements.clear();
		for (ShaderSearchEngine.ScoredOptionElement scored : sortedElements) {
			this.mainScreen.elements.add(scored.getElement());
		}
	}

	private void restoreOriginalLayout() {
		this.mainScreen.elements.clear();
		this.mainScreen.elements.addAll(this.originalMainElements);
	}
}
