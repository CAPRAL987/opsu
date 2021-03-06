/*
 * opsu! - an open-source osu! client
 * Copyright (C) 2014, 2015 Jeffrey Han
 *
 * opsu! is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * opsu! is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with opsu!.  If not, see <http://www.gnu.org/licenses/>.
 */

package itdelatrisu.opsu.states;

import itdelatrisu.opsu.GameImage;
import itdelatrisu.opsu.MenuButton;
import itdelatrisu.opsu.Opsu;
import itdelatrisu.opsu.Options;
import itdelatrisu.opsu.OsuGroupList;
import itdelatrisu.opsu.OsuGroupNode;
import itdelatrisu.opsu.OsuParser;
import itdelatrisu.opsu.OszUnpacker;
import itdelatrisu.opsu.UI;
import itdelatrisu.opsu.Utils;
import itdelatrisu.opsu.audio.MusicController;
import itdelatrisu.opsu.audio.SoundController;
import itdelatrisu.opsu.audio.SoundEffect;
import itdelatrisu.opsu.downloads.BloodcatServer;
import itdelatrisu.opsu.downloads.Download;
import itdelatrisu.opsu.downloads.DownloadList;
import itdelatrisu.opsu.downloads.DownloadNode;
import itdelatrisu.opsu.downloads.DownloadServer;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;

import org.newdawn.slick.Color;
import org.newdawn.slick.GameContainer;
import org.newdawn.slick.Graphics;
import org.newdawn.slick.Image;
import org.newdawn.slick.Input;
import org.newdawn.slick.SlickException;
import org.newdawn.slick.gui.TextField;
import org.newdawn.slick.state.BasicGameState;
import org.newdawn.slick.state.StateBasedGame;
import org.newdawn.slick.state.transition.FadeInTransition;
import org.newdawn.slick.state.transition.FadeOutTransition;
import org.newdawn.slick.util.Log;

/**
 * Downloads menu.
 */
public class DownloadsMenu extends BasicGameState {
	/** Delay time, in milliseconds, between each search. */
	private static final int SEARCH_DELAY = 700;

	/** Delay time, in milliseconds, for double-clicking focused result. */
	private static final int FOCUS_DELAY = 250;

	/** Minimum time, in milliseconds, that must elapse between queries. */
	private static final int MIN_REQUEST_INTERVAL = 300;

	/** The beatmap download server. */
	private DownloadServer server = new BloodcatServer();

	/** The current list of search results. */
	private DownloadNode[] resultList;

	/** Current focused (selected) result. */
	private int focusResult = -1;

	/** Delay time, in milliseconds, for double-clicking focused result. */
	private int focusTimer = 0;

	/** Current start result button (topmost entry). */
	private int startResult = 0;

	/** Total number of results for current query. */
	private int totalResults = 0;

	/** Page of current query results. */
	private int page = 1;

	/** Total number of results across pages seen so far. */
	private int pageResultTotal = 0;

	/** Page navigation. */
	private enum Page { RESET, CURRENT, PREVIOUS, NEXT };

	/** Page direction for next query. */
	private Page pageDir = Page.RESET;

	/** Whether to only show ranked maps. */
	private boolean rankedOnly = true;

	/** Current start download index. */
	private int startDownloadIndex = 0;

	/** Query thread. */
	private Thread queryThread;

	/** The search textfield. */
	private TextField search;

	/**
	 * Delay timer, in milliseconds, before running another search.
	 * This is overridden by character entry (reset) and 'esc'/'enter' (immediate search).
	 */
	private int searchTimer;

	/** Information text to display based on the search query. */
	private String searchResultString;

	/** Whether or not the search timer has been manually reset; reset after search delay passes. */
	private boolean searchTimerReset = false;

	/** The last search query. */
	private String lastQuery;

	/** Page direction for last query. */
	private Page lastQueryDir = Page.RESET;

	/** Number of active requests. */
	private int activeRequests = 0;

	/** Previous and next page buttons. */
	private MenuButton prevPage, nextPage;

	/** Buttons. */
	private MenuButton clearButton, importButton, resetButton, rankedButton;

	/** Beatmap importing thread. */
	private Thread importThread;

	/** Beatmap set ID of the current beatmap being previewed, or -1 if none. */
	private int previewID = -1;

	/** The bar notification to send upon entering the state. */
	private String barNotificationOnLoad;

	// game-related variables
	private GameContainer container;
	private StateBasedGame game;
	private Input input;
	private int state;

	public DownloadsMenu(int state) {
		this.state = state;
	}

	@Override
	public void init(GameContainer container, StateBasedGame game)
			throws SlickException {
		this.container = container;
		this.game = game;
		this.input = container.getInput();

		int width = container.getWidth();
		int height = container.getHeight();
		float baseX = width * 0.024f;
		float searchY = (height * 0.05f) + Utils.FONT_LARGE.getLineHeight();
		float searchWidth = width * 0.35f;

		// search
		searchTimer = SEARCH_DELAY;
		searchResultString = "Type to search!";
		search = new TextField(
				container, Utils.FONT_DEFAULT, (int) baseX, (int) searchY,
				(int) searchWidth, Utils.FONT_MEDIUM.getLineHeight()
		);
		search.setBackgroundColor(DownloadNode.BG_NORMAL);
		search.setBorderColor(Color.white);
		search.setTextColor(Color.white);
		search.setConsumeEvents(false);
		search.setMaxLength(255);

		// page buttons
		float pageButtonY = height * 0.2f;
		float pageButtonWidth = width * 0.7f;
		Image prevImg = GameImage.MUSIC_PREVIOUS.getImage();
		Image nextImg = GameImage.MUSIC_NEXT.getImage();
		prevPage = new MenuButton(prevImg, baseX + prevImg.getWidth() / 2f,
				pageButtonY - prevImg.getHeight() / 2f);
		nextPage = new MenuButton(nextImg, baseX + pageButtonWidth - nextImg.getWidth() / 2f,
				pageButtonY - nextImg.getHeight() / 2f);
		prevPage.setHoverExpand(1.5f);
		nextPage.setHoverExpand(1.5f);

		// buttons
		float buttonMarginX = width * 0.004f;
		float buttonHeight = height * 0.038f;
		float topButtonWidth = width * 0.14f;
		float lowerButtonWidth = width * 0.12f;
		float topButtonY = searchY + Utils.FONT_MEDIUM.getLineHeight() / 2f;
		float lowerButtonY = height * 0.995f - searchY - buttonHeight / 2f;
		Image button = GameImage.MENU_BUTTON_MID.getImage();
		Image buttonL = GameImage.MENU_BUTTON_LEFT.getImage();
		Image buttonR = GameImage.MENU_BUTTON_RIGHT.getImage();
		buttonL = buttonL.getScaledCopy(buttonHeight / buttonL.getHeight());
		buttonR = buttonR.getScaledCopy(buttonHeight / buttonR.getHeight());
		Image topButton = button.getScaledCopy((int) topButtonWidth - buttonL.getWidth() - buttonR.getWidth(), (int) buttonHeight);
		Image lowerButton = button.getScaledCopy((int) lowerButtonWidth - buttonL.getWidth() - buttonR.getWidth(), (int) buttonHeight);
		float fullTopButtonWidth = topButton.getWidth() + buttonL.getWidth() + buttonR.getWidth();
		float fullLowerButtonWidth = lowerButton.getWidth() + buttonL.getWidth() + buttonR.getWidth();
		clearButton = new MenuButton(lowerButton, buttonL, buttonR,
				width * 0.75f + buttonMarginX + fullLowerButtonWidth / 2f, lowerButtonY);
		importButton = new MenuButton(lowerButton, buttonL, buttonR,
				width - buttonMarginX - fullLowerButtonWidth / 2f, lowerButtonY);
		resetButton = new MenuButton(topButton, buttonL, buttonR,
				baseX + searchWidth + buttonMarginX + fullTopButtonWidth / 2f, topButtonY);
		rankedButton = new MenuButton(topButton, buttonL, buttonR,
				baseX + searchWidth + buttonMarginX * 2f + fullTopButtonWidth * 3 / 2f, topButtonY);
		clearButton.setText("Clear", Utils.FONT_MEDIUM, Color.white);
		importButton.setText("Import All", Utils.FONT_MEDIUM, Color.white);
		resetButton.setText("Reset Search", Utils.FONT_MEDIUM, Color.white);
		clearButton.setHoverFade();
		importButton.setHoverFade();
		resetButton.setHoverFade();
		rankedButton.setHoverFade();
	}

	@Override
	public void render(GameContainer container, StateBasedGame game, Graphics g)
			throws SlickException {
		int width = container.getWidth();
		int height = container.getHeight();
		int mouseX = input.getMouseX(), mouseY = input.getMouseY();

		// background
		GameImage.SEARCH_BG.getImage().draw();

		// title
		Utils.FONT_LARGE.drawString(width * 0.024f, height * 0.04f, "Download Beatmaps!", Color.white);

		// search
		g.setColor(Color.white);
		g.setLineWidth(2f);
		search.render(container, g);
		Utils.FONT_BOLD.drawString(
				search.getX() + search.getWidth() * 0.01f, search.getY() + search.getHeight() * 1.3f,
				searchResultString, Color.white
		);

		// search results
		DownloadNode[] nodes = resultList;
		if (nodes != null) {
			DownloadNode.clipToResultArea(g);
			int maxResultsShown = DownloadNode.maxResultsShown();
			for (int i = 0; i < maxResultsShown; i++) {
				int index = startResult + i;
				if (index >= nodes.length)
					break;
				nodes[index].drawResult(g, i, DownloadNode.resultContains(mouseX, mouseY, i),
						(index == focusResult), (previewID == nodes[index].getID()));
			}
			g.clearClip();

			// scroll bar
			if (nodes.length > maxResultsShown)
				DownloadNode.drawResultScrollbar(g, startResult, nodes.length);

			// pages
			if (nodes.length > 0) {
				float baseX = width * 0.024f;
				float buttonY = height * 0.2f;
				float buttonWidth = width * 0.7f;
				Utils.FONT_BOLD.drawString(
						baseX + (buttonWidth - Utils.FONT_BOLD.getWidth("Page 1")) / 2f,
						buttonY - Utils.FONT_BOLD.getLineHeight() * 1.3f,
						String.format("Page %d", page), Color.white
				);
				if (page > 1)
					prevPage.draw();
				if (pageResultTotal < totalResults)
					nextPage.draw();
			}
		}

		// downloads
		float downloadsX = width * 0.75f, downloadsY = search.getY();
		g.setColor(DownloadNode.BG_NORMAL);
		g.fillRect(downloadsX, downloadsY,
				width * 0.25f, height - downloadsY * 2f);
		Utils.FONT_LARGE.drawString(downloadsX + width * 0.015f, downloadsY + height * 0.015f, "Downloads", Color.white);
		int downloadsSize = DownloadList.get().size();
		if (downloadsSize > 0) {
			int maxDownloadsShown = DownloadNode.maxDownloadsShown();
			for (int i = 0; i < maxDownloadsShown; i++) {
				int index = startDownloadIndex + i;
				if (index >= downloadsSize)
					break;
				DownloadNode node = DownloadList.get().getNode(index);
				if (node == null)
					break;
				node.drawDownload(g, i, index, DownloadNode.downloadContains(mouseX, mouseY, i));
			}

			// scroll bar
			if (downloadsSize > maxDownloadsShown)
				DownloadNode.drawDownloadScrollbar(g, startDownloadIndex, downloadsSize);
		}

		// buttons
		clearButton.draw(Color.gray);
		importButton.draw(Color.orange);
		resetButton.draw(Color.red);
		rankedButton.setText((rankedOnly) ? "Show Unranked" : "Hide Unranked", Utils.FONT_MEDIUM, Color.white);
		rankedButton.draw(Color.magenta);

		// importing beatmaps
		if (importThread != null) {
			// darken the screen
			g.setColor(Utils.COLOR_BLACK_ALPHA);
			g.fillRect(0, 0, width, height);

			UI.drawLoadingProgress(g);
		}

		// back button
		else
			UI.getBackButton().draw();

		UI.draw(g);
	}

	@Override
	public void update(GameContainer container, StateBasedGame game, int delta)
			throws SlickException {
		UI.update(delta);
		if (importThread == null)
			MusicController.loopTrackIfEnded(false);
		int mouseX = input.getMouseX(), mouseY = input.getMouseY();
		UI.getBackButton().hoverUpdate(delta, mouseX, mouseY);
		prevPage.hoverUpdate(delta, mouseX, mouseY);
		nextPage.hoverUpdate(delta, mouseX, mouseY);
		clearButton.hoverUpdate(delta, mouseX, mouseY);
		importButton.hoverUpdate(delta, mouseX, mouseY);
		resetButton.hoverUpdate(delta, mouseX, mouseY);
		rankedButton.hoverUpdate(delta, mouseX, mouseY);

		// focus timer
		if (focusResult != -1 && focusTimer < FOCUS_DELAY)
			focusTimer += delta;

		// search
		search.setFocus(true);
		searchTimer += delta;
		if (searchTimer >= SEARCH_DELAY && importThread == null) {
			searchTimer = 0;
			searchTimerReset = false;

			final String query = search.getText().trim().toLowerCase();
			if (lastQuery == null || !query.equals(lastQuery)) {
				lastQuery = query;
				lastQueryDir = pageDir;

				if (queryThread != null && queryThread.isAlive())
					queryThread.interrupt();

				// execute query
				queryThread = new Thread() {
					@Override
					public void run() {
						activeRequests++;

						// check page direction
						Page lastPageDir = pageDir;
						pageDir = Page.RESET;
						int lastPageSize = (resultList != null) ? resultList.length : 0;
						int newPage = page;
						if (lastPageDir == Page.RESET)
							newPage = 1;
						else if (lastPageDir == Page.NEXT)
							newPage++;
						else if (lastPageDir == Page.PREVIOUS)
							newPage--;
						try {
							DownloadNode[] nodes = server.resultList(query, newPage, rankedOnly);
							if (activeRequests - 1 == 0) {
								// update page total
								page = newPage;
								if (nodes != null) {
									if (lastPageDir == Page.NEXT)
										pageResultTotal += nodes.length;
									else if (lastPageDir == Page.PREVIOUS)
										pageResultTotal -= lastPageSize;
									else if (lastPageDir == Page.RESET)
										pageResultTotal = nodes.length;
								} else
									pageResultTotal = 0;

								resultList = nodes;
								totalResults = server.totalResults();
								focusResult = -1;
								startResult = 0;
								if (nodes == null)
									searchResultString = "An error has occurred.";
								else {
									if (query.isEmpty())
										searchResultString = "Type to search!";
									else if (totalResults == 0)
										searchResultString = "No results found.";
									else
										searchResultString = String.format("%d result%s found!",
												totalResults, (totalResults == 1) ? "" : "s");
								}
							}
						} catch (IOException e) {
							searchResultString = "Could not establish connection to server.";
						} finally {
							activeRequests--;
							queryThread = null;
						}
					}
				};
				queryThread.start();
			}
		}
	}

	@Override
	public int getID() { return state; }

	@Override
	public void mousePressed(int button, int x, int y) {
		// check mouse button
		if (button == Input.MOUSE_MIDDLE_BUTTON)
			return;

		// block input during beatmap importing
		if (importThread != null)
			return;

		// back
		if (UI.getBackButton().contains(x, y)) {
			SoundController.playSound(SoundEffect.MENUBACK);
			((MainMenu) game.getState(Opsu.STATE_MAINMENU)).reset();
			game.enterState(Opsu.STATE_MAINMENU, new FadeOutTransition(Color.black), new FadeInTransition(Color.black));
			return;
		}

		// search results
		DownloadNode[] nodes = resultList;
		if (nodes != null) {
			if (DownloadNode.resultAreaContains(x, y)) {
				int maxResultsShown = DownloadNode.maxResultsShown();
				for (int i = 0; i < maxResultsShown; i++) {
					int index = startResult + i;
					if (index >= nodes.length)
						break;
					if (DownloadNode.resultContains(x, y, i)) {
						final DownloadNode node = nodes[index];

						// check if map is already loaded
						boolean isLoaded = OsuGroupList.get().containsBeatmapSetID(node.getID());

						// track preview
						if (DownloadNode.resultIconContains(x, y, i)) {
							// set focus
							if (!isLoaded) {
								SoundController.playSound(SoundEffect.MENUCLICK);
								focusResult = index;
								focusTimer = FOCUS_DELAY;
							}

							if (previewID == node.getID()) {
								// stop preview
								previewID = -1;
								SoundController.stopTrack();
							} else {
								// play preview
								try {
									final URL url = new URL(server.getPreviewURL(node.getID()));
									MusicController.pause();
									new Thread() {
										@Override
										public void run() {
											try {
												previewID = -1;
												SoundController.playTrack(url, true, new LineListener() {
													@Override
													public void update(LineEvent event) {
														if (event.getType() == LineEvent.Type.STOP) {
															if (previewID != -1) {
																SoundController.stopTrack();
																previewID = -1;
															}
														}
													}
												});
												previewID = node.getID();
											} catch (SlickException e) {
												UI.sendBarNotification("Failed to load track preview. See log for details.");
												Log.error(e);
											}
										}
									}.start();
								} catch (MalformedURLException e) {
									UI.sendBarNotification("Could not load track preview (bad URL).");
									Log.error(e);
								}
							}
							return;
						}

						if (isLoaded)
							return;

						SoundController.playSound(SoundEffect.MENUCLICK);
						if (index == focusResult) {
							if (focusTimer >= FOCUS_DELAY) {
								// too slow for double-click
								focusTimer = 0;
							} else {
								// start download
								if (!DownloadList.get().contains(node.getID())) {
									DownloadList.get().addNode(node);
									node.createDownload(server);
									node.getDownload().start();
								}
							}
						} else {
							// set focus
							focusResult = index;
							focusTimer = 0;
						}
						break;
					}
				}
				return;
			}

			// pages
			if (nodes.length > 0) {
				if (page > 1 && prevPage.contains(x, y)) {
					if (lastQueryDir == Page.PREVIOUS && queryThread != null && queryThread.isAlive())
						;  // don't send consecutive requests
					else {
						SoundController.playSound(SoundEffect.MENUCLICK);
						pageDir = Page.PREVIOUS;
						lastQuery = null;
						resetSearchTimer();
					}
					return;
				}
				if (pageResultTotal < totalResults && nextPage.contains(x, y)) {
					if (lastQueryDir == Page.NEXT && queryThread != null && queryThread.isAlive())
						;  // don't send consecutive requests
					else {
						SoundController.playSound(SoundEffect.MENUCLICK);
						pageDir = Page.NEXT;
						lastQuery = null;
						resetSearchTimer();
						return;
					}
				}
			}
		}

		// buttons
		if (clearButton.contains(x, y)) {
			SoundController.playSound(SoundEffect.MENUCLICK);
			DownloadList.get().clearInactiveDownloads();
			return;
		}
		if (importButton.contains(x, y)) {
			SoundController.playSound(SoundEffect.MENUCLICK);

			// import songs in new thread
			importThread = new Thread() {
				@Override
				public void run() {
					// invoke unpacker and parser
					File[] dirs = OszUnpacker.unpackAllFiles(Options.getOSZDir(), Options.getBeatmapDir());
					if (dirs != null && dirs.length > 0) {
						OsuGroupNode node = OsuParser.parseDirectories(dirs);
						if (node != null) {
							// stop preview
							previewID = -1;
							SoundController.stopTrack();

							// initialize song list
							OsuGroupList.get().reset();
							OsuGroupList.get().init();
							((SongMenu) game.getState(Opsu.STATE_SONGMENU)).setFocus(node, -1, true, true);

							// send notification
							UI.sendBarNotification((dirs.length == 1) ? "Imported 1 new song." :
									String.format("Imported %d new songs.", dirs.length));
						}
					}

					DownloadList.get().clearDownloads(Download.Status.COMPLETE);
					importThread = null;
				}
			};
			importThread.start();
			return;
		}
		if (resetButton.contains(x, y)) {
			SoundController.playSound(SoundEffect.MENUCLICK);
			search.setText("");
			lastQuery = null;
			pageDir = Page.RESET;
			resetSearchTimer();
			return;
		}
		if (rankedButton.contains(x, y)) {
			SoundController.playSound(SoundEffect.MENUCLICK);
			rankedOnly = !rankedOnly;
			lastQuery = null;
			pageDir = Page.RESET;
			resetSearchTimer();
			return;
		}

		// downloads
		if (!DownloadList.get().isEmpty() && DownloadNode.downloadAreaContains(x, y)) {
			int maxDownloadsShown = DownloadNode.maxDownloadsShown();
			for (int i = 0, n = DownloadList.get().size(); i < maxDownloadsShown; i++) {
				int index = startDownloadIndex + i;
				if (index >= n)
					break;
				if (DownloadNode.downloadIconContains(x, y, i)) {
					SoundController.playSound(SoundEffect.MENUCLICK);
					DownloadNode node = DownloadList.get().getNode(index);
					if (node == null)
						return;
					Download dl = node.getDownload();
					switch (dl.getStatus()) {
					case CANCELLED:
					case COMPLETE:
					case ERROR:
						node.clearDownload();
						DownloadList.get().remove(index);
						break;
					case DOWNLOADING:
					case WAITING:
						dl.cancel();
						break;
					}
					return;
				}
			}
		}
	}

	@Override
	public void mouseWheelMoved(int newValue) {
		// change volume
		if (input.isKeyDown(Input.KEY_LALT) || input.isKeyDown(Input.KEY_RALT)) {
			UI.changeVolume((newValue < 0) ? -1 : 1);
			return;
		}

		// block input during beatmap importing
		if (importThread != null)
			return;

		int shift = (newValue < 0) ? 1 : -1;
		int mouseX = input.getMouseX(), mouseY = input.getMouseY();
		scrollLists(mouseX, mouseY, shift);
	}

	@Override
	public void mouseDragged(int oldx, int oldy, int newx, int newy) {
		// block input during beatmap importing
		if (importThread != null)
			return;

		// check mouse button
		if (!input.isMouseButtonDown(Input.MOUSE_RIGHT_BUTTON) &&
			!input.isMouseButtonDown(Input.MOUSE_LEFT_BUTTON))
			return;

		int diff = newy - oldy;
		if (diff == 0)
			return;
		int shift = (diff < 0) ? 1 : -1;
		scrollLists(oldx, oldy, shift);
	}

	@Override
	public void keyPressed(int key, char c) {
		// block input during beatmap importing
		if (importThread != null && !(key == Input.KEY_ESCAPE || key == Input.KEY_F12))
			return;

		switch (key) {
		case Input.KEY_ESCAPE:
			if (importThread != null) {
				// beatmap importing: stop parsing OsuFiles by sending interrupt to OsuParser
				importThread.interrupt();
			} else if (!search.getText().isEmpty()) {
				// clear search text
				search.setText("");
				pageDir = Page.RESET;
				resetSearchTimer();
			} else {
				// return to main menu
				SoundController.playSound(SoundEffect.MENUBACK);
				((MainMenu) game.getState(Opsu.STATE_MAINMENU)).reset();
				game.enterState(Opsu.STATE_MAINMENU, new FadeOutTransition(Color.black), new FadeInTransition(Color.black));
			}
			break;
		case Input.KEY_ENTER:
			if (!search.getText().isEmpty()) {
				pageDir = Page.RESET;
				resetSearchTimer();
			}
			break;
		case Input.KEY_F5:
			SoundController.playSound(SoundEffect.MENUCLICK);
			lastQuery = null;
			pageDir = Page.CURRENT;
			resetSearchTimer();
			break;
		case Input.KEY_F7:
			Options.setNextFPS(container);
			break;
		case Input.KEY_F10:
			Options.toggleMouseDisabled();
			break;
		case Input.KEY_F12:
			Utils.takeScreenShot();
			break;
		default:
			// wait for user to finish typing
			if (Character.isLetterOrDigit(c) || key == Input.KEY_BACK) {
				searchTimer = 0;
				pageDir = Page.RESET;
			}
			break;
		}
	}

	@Override
	public void enter(GameContainer container, StateBasedGame game)
			throws SlickException {
		UI.enter();
		prevPage.resetHover();
		nextPage.resetHover();
		clearButton.resetHover();
		importButton.resetHover();
		resetButton.resetHover();
		rankedButton.resetHover();
		focusResult = -1;
		startResult = 0;
		startDownloadIndex = 0;
		pageDir = Page.RESET;
		previewID = -1;
		if (barNotificationOnLoad != null) {
			UI.sendBarNotification(barNotificationOnLoad);
			barNotificationOnLoad = null;
		}
	}

	@Override
	public void leave(GameContainer container, StateBasedGame game)
			throws SlickException {
		search.setFocus(false);
		SoundController.stopTrack();
		MusicController.resume();
	}

	/**
	 * Resets the search timer, but respects the minimum request interval.
	 */
	private void resetSearchTimer() {
		if (!searchTimerReset) {
			if (searchTimer < MIN_REQUEST_INTERVAL)
				searchTimer = SEARCH_DELAY - MIN_REQUEST_INTERVAL;
			else
				searchTimer = SEARCH_DELAY;
			searchTimerReset = true;
		}
	}

	/**
	 * Processes a shift in the search result and downloads list start indices,
	 * if the mouse coordinates are within the area bounds.
	 * @param cx the x coordinate
	 * @param cy the y coordinate
	 * @param shift the number of indices to shift
	 */
	private void scrollLists(int cx, int cy, int shift) {
		// search results
		if (DownloadNode.resultAreaContains(cx, cy)) {
			DownloadNode[] nodes = resultList;
			if (nodes != null && nodes.length >= DownloadNode.maxResultsShown()) {
				int newStartResult = startResult + shift;
				if (newStartResult >= 0 && newStartResult + DownloadNode.maxResultsShown() <= nodes.length)
					startResult = newStartResult;
			}
		}

		// downloads
		else if (DownloadNode.downloadAreaContains(cx, cy)) {
			if (DownloadList.get().size() >= DownloadNode.maxDownloadsShown()) {
				int newStartDownloadIndex = startDownloadIndex + shift;
				if (newStartDownloadIndex >= 0 && newStartDownloadIndex + DownloadNode.maxDownloadsShown() <= DownloadList.get().size())
					startDownloadIndex = newStartDownloadIndex;
			}
		}
	}

	/**
	 * Sends a bar notification upon entering the state.
	 * @param s the notification string
	 */
	public void notifyOnLoad(String s) { barNotificationOnLoad = s; }
}
