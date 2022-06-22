package com.sourcegraph.find;

import com.intellij.CommonBundle;
import com.intellij.accessibility.TextFieldWithListAccessibleContext;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.find.*;
import com.intellij.find.actions.ShowUsagesAction;
import com.intellij.find.impl.*;
import com.intellij.find.replaceInProject.ReplaceInProjectManager;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.IdeEventQueue;
import com.intellij.ide.scratch.ScratchUtil;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionButtonLook;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.actionSystem.ex.TooltipDescriptionProvider;
import com.intellij.openapi.actionSystem.ex.TooltipLinkProvider;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText;
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.help.HelpManager;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.project.*;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.impl.IdeFrameImpl;
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl;
import com.intellij.pom.Navigatable;
import com.intellij.ui.*;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.*;
import com.intellij.ui.dsl.gridLayout.builders.RowBuilder;
import com.intellij.ui.hover.TableHoverListener;
import com.intellij.ui.mac.touchbar.Touchbar;
import com.intellij.ui.popup.PopupState;
import com.intellij.ui.render.RenderingUtil;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.ui.table.JBTable;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.FindUsagesProcessPresentation;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageViewPresentation;
import com.intellij.usages.impl.UsagePreviewPanel;
import com.intellij.util.*;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.JBIterable;
import com.intellij.util.ui.*;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.accessibility.AccessibleContext;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.List;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.intellij.openapi.actionSystem.IdeActions.ACTION_OPEN_IN_RIGHT_SPLIT;
import static com.intellij.ui.SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES;
import static com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN;
import static com.intellij.util.FontUtil.spaceAndThinSpace;

public class NativeFindPopupPanel extends JBPanel<NativeFindPopupPanel> implements FindUI {
    private static final KeyStroke ENTER = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
    private static final KeyStroke ENTER_WITH_MODIFIERS = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, SystemInfo.isMac
        ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke REPLACE_ALL = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
    private static final KeyStroke RESET_FILTERS = KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.ALT_DOWN_MASK);

    private static final String SERVICE_KEY = "find.popup";
    private static final String SPLITTER_SERVICE_KEY = "find.popup.splitter";
    @NotNull
    private final FindUIHelper myHelper;
    @NotNull
    private final Project myProject;
    @NotNull
    private final Disposable myDisposable;
    private final Alarm myPreviewUpdater;
    @NotNull
//    private final FindPopupScopeUI myScopeUI;
    private JComponent myCodePreviewComponent;
    private SearchTextArea mySearchTextArea;
    private SearchTextArea myReplaceTextArea;
    private ActionListener myOkActionListener;
    private final AtomicBoolean myCanClose = new AtomicBoolean(true);
    private final AtomicBoolean myIsPinned = new AtomicBoolean(false);
    private JBLabel myOKHintLabel;
    private JBLabel myNavigationHintLabel;
    private Alarm mySearchRescheduleOnCancellationsAlarm;
    private volatile ProgressIndicatorBase myResultsPreviewSearchProgress;

    //    private FindPopupHeader header;
    private final AtomicBoolean myCaseSensitiveState = new AtomicBoolean();
    private final AtomicBoolean myPreserveCaseState = new AtomicBoolean();
    private final AtomicBoolean myWholeWordsState = new AtomicBoolean();
    private final AtomicBoolean myRegexState = new AtomicBoolean();
    private JCheckBox myNewTabCheckbox;
    private ActionToolbarImpl myScopeSelectionToolbar;
    private ActionButton myFilterContextButton;
    private JButton myOKButton;
    private JButton myReplaceAllButton;
    private JButton myReplaceSelectedButton;
    private JTextArea mySearchComponent;
    private JTextArea myReplaceComponent;
    private String mySelectedContextName = FindBundle.message("find.context.anywhere.scope.label");
    private FindPopupScopeUI.ScopeType mySelectedScope;
    private JPanel myScopeDetailsPanel;

    private JBTable myResultsPreviewTable;
    private DefaultTableModel myResultsPreviewTableModel;
    private SimpleColoredComponent myUsagePreviewTitle;
    private UsagePreviewPanel myUsagePreviewPanel;
    private DialogWrapper myDialog;
    private int myLoadingHash;
    private final AtomicBoolean myNeedReset = new AtomicBoolean(true);
    private String myUsagesCount;
    private String myFilesCount;
    private UsageViewPresentation myUsageViewPresentation;
    private final ComponentValidator myComponentValidator;
    private AnAction myCaseSensitiveAction;
    private AnAction myWholeWordsAction;
    private AnAction myRegexAction;
    private AnAction myResetFiltersAction;
    private boolean mySuggestRegexHintForEmptyResults = true;
    private OnePixelSplitter myPreviewSplitter;

    NativeFindPopupPanel(@NotNull FindUIHelper helper) {
        myHelper = helper;
        myProject = myHelper.getProject();
        myDisposable = Disposer.newDisposable();
        myPreviewUpdater = new Alarm(myDisposable);
//        myScopeUI = FindPopupScopeUIProvider.getInstance().create(this);
        myComponentValidator = new ComponentValidator(myDisposable) {
            @Override
            public void updateInfo(@Nullable ValidationInfo info) {
                if (info != null && info.component == mySearchComponent) {
                    super.updateInfo(null);
                } else {
                    super.updateInfo(info);
                }
            }
        };

        Disposer.register(myDisposable, () -> {
            finishPreviousPreviewSearch();
            if (mySearchRescheduleOnCancellationsAlarm != null)
                Disposer.dispose(mySearchRescheduleOnCancellationsAlarm);
            if (myUsagePreviewPanel != null) Disposer.dispose(myUsagePreviewPanel);
        });

        initComponents();
        initByModel();

        FindUsagesCollector.triggerUsedOptionsStats(myProject, FindUsagesCollector.FIND_IN_PATH, myHelper.getModel());
    }

    @Override
    public void showUI() {
        if (myDialog != null && myDialog.isVisible()) {
            return;
        }
        if (myDialog != null && !myDialog.isDisposed()) {
            myDialog.doCancelAction();
        }
        if (myDialog == null || myDialog.isDisposed()) {
            myDialog = new DialogWrapper(myHelper.getProject(), null, true, DialogWrapper.IdeModalityType.MODELESS, false) {
                {
                    init();
                    getRootPane().setDefaultButton(null);
                }

                @Override
                protected void doOKAction() {
                    myOkActionListener.actionPerformed(null);
                }

                @Override
                protected void dispose() {
                    saveSettings();
                    super.dispose();
                }

                @Nullable
                @Override
                protected Border createContentPaneBorder() {
                    return null;
                }

                @Override
                protected JComponent createCenterPanel() {
                    return NativeFindPopupPanel.this;
                }

                @Override
                protected String getDimensionServiceKey() {
                    return SERVICE_KEY;
                }
            };
            myDialog.setUndecorated(true);
            ApplicationManager.getApplication().getMessageBus().connect(myDialog.getDisposable()).subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
                @Override
                public void projectClosed(@NotNull Project project) {
                    closeImmediately();
                }
            });
            Disposer.register(myDialog.getDisposable(), myDisposable);

            Window window = WindowManager.getInstance().suggestParentWindow(myProject);
            Component parent = UIUtil.findUltimateParent(window);
            RelativePoint showPoint = null;
            Point screenPoint = DimensionService.getInstance().getLocation(SERVICE_KEY);
            if (screenPoint != null) {
                if (parent != null) {
                    SwingUtilities.convertPointFromScreen(screenPoint, parent);
                    showPoint = new RelativePoint(parent, screenPoint);
                } else {
                    showPoint = new RelativePoint(screenPoint);
                }
            }
            if (parent != null && showPoint == null) {
                int height = UISettings.getInstance().getShowNavigationBar() ? 135 : 115;
                if (parent instanceof IdeFrameImpl && ((IdeFrameImpl) parent).isInFullScreen()) {
                    height -= 20;
                }
                showPoint = new RelativePoint(parent, new Point((parent.getSize().width - getPreferredSize().width) / 2, height));
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (mySearchComponent.getCaret() != null) {
                    mySearchComponent.selectAll();
                }
            });
            WindowMoveListener windowListener = new WindowMoveListener(this);
            addMouseListener(windowListener);
            addMouseMotionListener(windowListener);
            Dimension panelSize = getPreferredSize();
            Dimension prev = DimensionService.getInstance().getSize(SERVICE_KEY);
            panelSize.width += JBUIScale.scale(24);//hidden 'loading' icon
            panelSize.height *= 2;
            if (prev != null && prev.height < panelSize.height) prev.height = panelSize.height;
            Window dialogWindow = myDialog.getPeer().getWindow();
            AnAction escape = ActionManager.getInstance().getAction("EditorEscape");
            JRootPane root = ((RootPaneContainer) dialogWindow).getRootPane();

            IdeGlassPaneImpl glass = (IdeGlassPaneImpl) myDialog.getRootPane().getGlassPane();
            WindowResizeListener resizeListener = new WindowResizeListener(
                root,
                JBUI.insets(4),
                null) {
                private Cursor myCursor;

                @Override
                protected void setCursor(@NotNull Component content, Cursor cursor) {
                    if (myCursor != cursor || myCursor != Cursor.getDefaultCursor()) {
                        glass.setCursor(cursor, this);
                        myCursor = cursor;

                        if (content instanceof JComponent) {
                            IdeGlassPaneImpl.savePreProcessedCursor((JComponent) content, content.getCursor());
                        }
                        super.setCursor(content, cursor);
                    }
                }
            };
            glass.addMousePreprocessor(resizeListener, myDisposable);
            glass.addMouseMotionPreprocessor(resizeListener, myDisposable);

            DumbAwareAction.create(e -> closeImmediately())
                .registerCustomShortcutSet(escape == null ? CommonShortcuts.ESCAPE : escape.getShortcutSet(), root, myDisposable);
            root.setWindowDecorationStyle(JRootPane.NONE);
            root.setBorder(PopupBorder.Factory.create(true, true));
            UIUtil.markAsPossibleOwner((Dialog) dialogWindow);
            dialogWindow.setBackground(UIUtil.getPanelBackground());
            dialogWindow.setMinimumSize(panelSize);
            if (prev == null) {
                panelSize.height *= 1.5;
                panelSize.width *= 1.15;
            }
            dialogWindow.setSize(prev != null ? prev : panelSize);

            IdeEventQueue.getInstance().getPopupManager().closeAllPopups(false);
            if (showPoint != null) {
                myDialog.setLocation(showPoint.getScreenPoint());
            } else {
                dialogWindow.setLocationRelativeTo(null);
            }
            mySuggestRegexHintForEmptyResults = true;
            myDialog.show();

//            myDialog.setOnDeactivationAction(() -> closeIfPossible());

            JRootPane rootPane = getRootPane();
            if (rootPane != null) {
                if (myHelper.isReplaceState()) {
                    rootPane.setDefaultButton(myReplaceSelectedButton);
                }
                rootPane.getInputMap(WHEN_IN_FOCUSED_WINDOW).put(ENTER_WITH_MODIFIERS, "openInFindWindow");
                rootPane.getActionMap().put("openInFindWindow", new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        myOkActionListener.actionPerformed(null);
                    }
                });
            }
            ApplicationManager.getApplication().invokeLater(this::scheduleResultsUpdate, ModalityState.any());
        }
    }

    public void closeIfPossible() {
        if (canBeClosed() && !myIsPinned.get()) {
            myDialog.doCancelAction();
        }
    }

    protected boolean canBeClosed() {
        if (myProject.isDisposed()) return true;
        if (!myCanClose.get()) return false;
        if (myIsPinned.get()) return false;
        if (!ApplicationManager.getApplication().isActive()) return false;
        if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow() == null) return false;
//        if (header.fileMaskField.isPopupVisible()) {
//            header.fileMaskField.setPopupVisible(false);
//            return false;
//        }
        List<JBPopup> popups = ContainerUtil.filter(JBPopupFactory.getInstance().getChildPopups(this), popup -> !popup.isDisposed());
        if (!popups.isEmpty()) {
            for (JBPopup popup : popups) {
                popup.cancel();
            }
            return false;
        }
        return true; //!myScopeUI.hideAllPopups();
    }

    @Override
    public void saveSettings() {
        Window window = myDialog.getWindow();
        if (!window.isShowing()) return;
        DimensionService.getInstance().setSize(SERVICE_KEY, myDialog.getSize(), myHelper.getProject());
        DimensionService.getInstance().setLocation(SERVICE_KEY, window.getLocationOnScreen(), myHelper.getProject());
        FindSettings findSettings = FindSettings.getInstance();
//        myScopeUI.applyTo(findSettings, mySelectedScope);
//        myHelper.updateFindSettings();
        applyTo(FindManager.getInstance(myProject).getFindInProjectModel());
    }

    @NotNull
    @Override
    public Disposable getDisposable() {
        return myDisposable;
    }

    @NotNull
    public Project getProject() {
        return myProject;
    }

    @NotNull
    public FindUIHelper getHelper() {
        return myHelper;
    }

    @NotNull
    public AtomicBoolean getCanClose() {
        return myCanClose;
    }

    private void initComponents() {
        AnAction myShowFilterPopupAction = new MyShowFilterPopupAction();
        myFilterContextButton =
            new ActionButton(myShowFilterPopupAction, myShowFilterPopupAction.getTemplatePresentation(), ActionPlaces.UNKNOWN,
                ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE) {
                @Override
                public Icon getIcon() {
                    Icon icon = myShowFilterPopupAction.getTemplatePresentation().getIcon();
                    return mySelectedContextName.equals(FindInProjectUtil.getPresentableName(FindModel.SearchContext.ANY))
                        ? icon
                        : ExecutionUtil.getLiveIndicator(icon);
                }
            };
        myShowFilterPopupAction.registerCustomShortcutSet(myShowFilterPopupAction.getShortcutSet(), this);

//        header = new FindPopupHeader(myProject, myFilterContextButton, new MyPinAction());

        ItemListener liveResultsPreviewUpdateListener = __ -> scheduleResultsUpdate();
//        header.cbFileFilter.addItemListener(__ -> {
//            if (header.cbFileFilter.isSelected()) {
//                header.fileMaskField.setEnabled(true);
//                if (header.cbFileFilter.getClientProperty("dontRequestFocus") == null) {
//                    IdeFocusManager.getInstance(myProject).requestFocus(header.fileMaskField, true);
//                    header.fileMaskField.getEditor().selectAll();
//                }
//            } else {
//                header.fileMaskField.setEnabled(false);
//                if (header.cbFileFilter.getClientProperty("dontRequestFocus") == null) {
//                    IdeFocusManager.getInstance(myProject).requestFocus(mySearchComponent, true);
//                }
//            }
//        });
//        header.cbFileFilter.addItemListener(liveResultsPreviewUpdateListener);
//        header.fileMaskField.addActionListener(__ -> scheduleResultsUpdate());
//        Component editorComponent = header.fileMaskField.getEditor().getEditorComponent();
//        if (editorComponent instanceof EditorTextField) {
//            EditorTextField etf = (EditorTextField) editorComponent;
//            etf.addDocumentListener(new DocumentListener() {
//                @Override
//                public void documentChanged(@NotNull com.intellij.openapi.editor.event.DocumentEvent event) {
//                    onFileMaskChanged();
//                }
//            });
//        } else {
//            if (editorComponent instanceof JTextComponent) {
//                ((JTextComponent) editorComponent).getDocument().addDocumentListener(new DocumentAdapter() {
//                    @Override
//                    protected void textChanged(@NotNull DocumentEvent e) {
//                        onFileMaskChanged();
//                    }
//                });
//            } else {
//                assert false;
//            }
//        }

        myResetFiltersAction = DumbAwareAction.create(event -> resetAllFilters());
        myResetFiltersAction.registerCustomShortcutSet(new CustomShortcutSet(RESET_FILTERS), this);

        myOKButton = new JButton(FindBundle.message("find.popup.find.button"));
        myReplaceAllButton = new JButton(FindBundle.message("find.popup.replace.all.button"));
        myReplaceSelectedButton = new JButton(FindBundle.message("find.popup.replace.selected.button", 0));

        myOkActionListener = __ -> doOK(true);
        myReplaceAllButton.setOpaque(false);
        myReplaceAllButton.addActionListener(__ -> doOK(false));
        myReplaceSelectedButton.setOpaque(false);
        myReplaceSelectedButton.addActionListener(e -> {
            int rowToSelect = myResultsPreviewTable.getSelectionModel().getMinSelectionIndex();
            Map<Integer, Usage> usages = getSelectedUsages();
            if (usages == null) {
                return;
            }
            CommandProcessor.getInstance().executeCommand(myProject, () -> {
                for (Map.Entry<Integer, Usage> entry : usages.entrySet()) {
                    try {
                        ReplaceInProjectManager.getInstance(myProject).replaceUsage(entry.getValue(), myHelper.getModel(), Collections.emptySet(), false);
                        ((DefaultTableModel) myResultsPreviewTable.getModel()).removeRow(entry.getKey());
                    } catch (FindManager.MalformedReplacementStringException ex) {
                        if (!ApplicationManager.getApplication().isUnitTestMode()) {
                            Messages.showErrorDialog(this, ex.getMessage(), FindBundle.message("find.replace.invalid.replacement.string.title"));
                        }
                        break;
                    }
                }

                ApplicationManager.getApplication().invokeLater(() -> {
                    if (myResultsPreviewTable.getRowCount() > rowToSelect) {
                        myResultsPreviewTable.getSelectionModel().setSelectionInterval(rowToSelect, rowToSelect);
                    }
                    ScrollingUtil.ensureSelectionExists(myResultsPreviewTable);
                });
            }, FindBundle.message("find.replace.command"), null);
        });
        myOKButton.setOpaque(false);
        myOKButton.addActionListener(myOkActionListener);
        boolean enterAsOK = false;

        AnAction openInRightSplit = ActionManager.getInstance().getAction(ACTION_OPEN_IN_RIGHT_SPLIT);
        if (openInRightSplit != null) {
            ShortcutSet set = openInRightSplit.getShortcutSet();
            new MyEnterAction(false).registerCustomShortcutSet(set, this);
        }

        new MyEnterAction(enterAsOK).registerCustomShortcutSet(new CustomShortcutSet(ENTER), this);
        DumbAwareAction.create(
            __ -> myOkActionListener.actionPerformed(null)).registerCustomShortcutSet(new CustomShortcutSet(ENTER_WITH_MODIFIERS), this);
        DumbAwareAction.create(__ -> myReplaceAllButton.doClick()).registerCustomShortcutSet(new CustomShortcutSet(REPLACE_ALL), this);
        myReplaceAllButton.setToolTipText(KeymapUtil.getKeystrokeText(REPLACE_ALL));

        List<Shortcut> navigationKeyStrokes = new ArrayList<>();
        KeyStroke viewSourceKeyStroke = KeymapUtil.getKeyStroke(CommonShortcuts.getViewSource());
        if (viewSourceKeyStroke != null && !Comparing.equal(viewSourceKeyStroke, ENTER_WITH_MODIFIERS) && !Comparing.equal(viewSourceKeyStroke, ENTER)) {
            navigationKeyStrokes.add(new KeyboardShortcut(viewSourceKeyStroke, null));
        }
        KeyStroke editSourceKeyStroke = KeymapUtil.getKeyStroke(CommonShortcuts.getEditSource());
        if (editSourceKeyStroke != null && !Comparing.equal(editSourceKeyStroke, ENTER_WITH_MODIFIERS) && !Comparing.equal(editSourceKeyStroke, ENTER)) {
            navigationKeyStrokes.add(new KeyboardShortcut(editSourceKeyStroke, null));
        }
        if (!navigationKeyStrokes.isEmpty()) {
            DumbAwareAction.create(e -> navigateToSelectedUsage(e))
                .registerCustomShortcutSet(new CustomShortcutSet(navigationKeyStrokes.toArray(Shortcut.EMPTY_ARRAY)), this);
        }

//        myResultsPreviewTableModel = createTableModel();
        myResultsPreviewTable = new JBTable(myResultsPreviewTableModel) {
            @Override
            public Dimension getPreferredScrollableViewportSize() {
                return new Dimension(getWidth(), 1 + getRowHeight() * 4);
            }
        };
        myResultsPreviewTable.setFocusable(false);
        myResultsPreviewTable.putClientProperty(RenderingUtil.ALWAYS_PAINT_SELECTION_AS_FOCUSED, true);
        myResultsPreviewTable.getEmptyText().setShowAboveCenter(false);
        myResultsPreviewTable.setShowColumns(false);
        myResultsPreviewTable.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        myResultsPreviewTable.setShowGrid(false);
        myResultsPreviewTable.setIntercellSpacing(JBUI.emptySize());

        mySearchComponent = new JBTextAreaWithMixedAccessibleContext(myResultsPreviewTable.getAccessibleContext());
        mySearchComponent.setColumns(25);
        mySearchComponent.setRows(1);
        mySearchComponent.getAccessibleContext().setAccessibleName(FindBundle.message("find.search.accessible.name"));
        myReplaceComponent = new JBTextAreaWithMixedAccessibleContext(myResultsPreviewTable.getAccessibleContext());
        myReplaceComponent.setColumns(25);
        myReplaceComponent.setRows(1);
        myReplaceComponent.getAccessibleContext().setAccessibleName(FindBundle.message("find.replace.accessible.name"));
        mySearchTextArea = new SearchTextArea(mySearchComponent, true);
        myReplaceTextArea = new SearchTextArea(myReplaceComponent, false);
        myCaseSensitiveAction =
            new MySwitchStateToggleAction("find.popup.case.sensitive", ToggleOptionName.CaseSensitive,
                AllIcons.Actions.MatchCase, AllIcons.Actions.MatchCaseHovered, AllIcons.Actions.MatchCaseSelected,
                myCaseSensitiveState, () -> !myHelper.getModel().isReplaceState() || !myPreserveCaseState.get());
        myWholeWordsAction =
            new MySwitchStateToggleAction("find.whole.words", ToggleOptionName.WholeWords,
                AllIcons.Actions.Words, AllIcons.Actions.WordsHovered, AllIcons.Actions.WordsSelected,
                myWholeWordsState, () -> !myRegexState.get());
        myRegexAction =
            new MySwitchStateToggleAction("find.regex", ToggleOptionName.Regex,
                AllIcons.Actions.Regex, AllIcons.Actions.RegexHovered, AllIcons.Actions.RegexSelected,
                myRegexState, () -> !myHelper.getModel().isReplaceState() || !myPreserveCaseState.get(),
                new TooltipLinkProvider.TooltipLink(FindBundle.message("find.regex.help.link"),
                    RegExHelpPopup.createRegExLinkRunnable(mySearchTextArea)));
        List<Component> searchExtraButtons =
            mySearchTextArea.setExtraActions(myCaseSensitiveAction, myWholeWordsAction, myRegexAction);
        AnAction preserveCaseAction =
            new MySwitchStateToggleAction("find.options.replace.preserve.case", ToggleOptionName.PreserveCase,
                AllIcons.Actions.PreserveCase, AllIcons.Actions.PreserveCaseHover, AllIcons.Actions.PreserveCaseSelected,
                myPreserveCaseState, () -> !myRegexState.get() && !myCaseSensitiveState.get());
        List<Component> replaceExtraButtons = myReplaceTextArea.setExtraActions(
            preserveCaseAction);
//        Pair<FindPopupScopeUI.ScopeType, JComponent>[] scopeComponents = myScopeUI.getComponents();

        myScopeDetailsPanel = new JPanel(new CardLayout());

//        List<AnAction> scopeActions = new ArrayList<>(scopeComponents.length);
//        for (Pair<FindPopupScopeUI.ScopeType, JComponent> scopeComponent : scopeComponents) {
//            FindPopupScopeUI.ScopeType scopeType = scopeComponent.first;
//            scopeActions.add(new MySelectScopeToggleAction(scopeType));
//            myScopeDetailsPanel.add(scopeType.name, scopeComponent.second);
//        }
//        myScopeSelectionToolbar = createScopeToolbar(scopeActions.toArray(AnAction.EMPTY_ARRAY));
//        myScopeSelectionToolbar.setMinimumButtonSize(ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE);
//        mySelectedScope = scopeComponents[0].first;

        TableHoverListener.DEFAULT.removeFrom(myResultsPreviewTable);
        new DoubleClickListener() {
            @Override
            protected boolean onDoubleClick(@NotNull MouseEvent event) {
                if (event.getSource() != myResultsPreviewTable) return false;
                navigateToSelectedUsage(null);
                return true;
            }
        }.installOn(myResultsPreviewTable);
        myResultsPreviewTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                myResultsPreviewTable.transferFocus();
            }
        });
        applyFont(JBFont.label(), myResultsPreviewTable);
        JComponent[] tableAware = {mySearchComponent, myReplaceComponent, myReplaceSelectedButton};
        for (JComponent component : tableAware) {
            ScrollingUtil.installActions(myResultsPreviewTable, false, component);
        }

        ActionListener helpAction = __ -> HelpManager.getInstance().invokeHelp("reference.dialogs.findinpath");
        registerKeyboardAction(helpAction, KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        registerKeyboardAction(helpAction, KeyStroke.getKeyStroke(KeyEvent.VK_HELP, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        KeymapManager keymapManager = KeymapManager.getInstance();
        Keymap activeKeymap = keymapManager != null ? keymapManager.getActiveKeymap() : null;
        if (activeKeymap != null) {
            ShortcutSet findNextShortcutSet = new CustomShortcutSet(activeKeymap.getShortcuts("FindNext"));
            ShortcutSet findPreviousShortcutSet = new CustomShortcutSet(activeKeymap.getShortcuts("FindPrevious"));
            DumbAwareAction findNextAction = DumbAwareAction.create(event -> {
                int selectedRow = myResultsPreviewTable.getSelectedRow();
                if (selectedRow >= 0 && selectedRow < myResultsPreviewTable.getRowCount() - 1) {
                    myResultsPreviewTable.setRowSelectionInterval(selectedRow + 1, selectedRow + 1);
                    ScrollingUtil.ensureIndexIsVisible(myResultsPreviewTable, selectedRow + 1, 1);
                }
            });
            DumbAwareAction findPreviousAction = DumbAwareAction.create(event -> {
                int selectedRow = myResultsPreviewTable.getSelectedRow();
                if (selectedRow > 0 && selectedRow <= myResultsPreviewTable.getRowCount() - 1) {
                    myResultsPreviewTable.setRowSelectionInterval(selectedRow - 1, selectedRow - 1);
                    ScrollingUtil.ensureIndexIsVisible(myResultsPreviewTable, selectedRow - 1, 1);
                }
            });
            for (JComponent component : tableAware) {
                findNextAction.registerCustomShortcutSet(findNextShortcutSet, component);
                findPreviousAction.registerCustomShortcutSet(findPreviousShortcutSet, component);
            }
        }
        myUsagePreviewTitle = new SimpleColoredComponent();
        myUsageViewPresentation = new UsageViewPresentation();
        myUsagePreviewPanel = new UsagePreviewPanel(myProject, myUsageViewPresentation, true) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(myResultsPreviewTable.getWidth(), Math.max(getHeight(), getLineHeight() * 15));
            }
        };
        Disposer.register(myDisposable, myUsagePreviewPanel);
        Runnable updatePreviewRunnable = () -> {
            if (Disposer.isDisposed(myDisposable)) return;
            int[] selectedRows = myResultsPreviewTable.getSelectedRows();
            List<Promise<UsageInfo[]>> selectedUsagePromises = new SmartList<>();
            String file = null;
            for (int row : selectedRows) {
                Object value = myResultsPreviewTable.getModel().getValueAt(row, 0);
//                UsageInfoAdapter adapter = ((FindPopupItem) value).getUsage();
//                file = adapter.getPath();
//                if (adapter.isValid()) {
//                    selectedUsagePromises.add(adapter.getMergedInfosAsync());
//                }
            }

            String selectedFile = file;
            Promises.collectResults(selectedUsagePromises).onSuccess(data -> {
                List<UsageInfo> selectedUsages = new SmartList<>();
                for (UsageInfo[] usageInfos : data) {
                    Collections.addAll(selectedUsages, usageInfos);
                }
                myReplaceSelectedButton.setText(FindBundle.message("find.popup.replace.selected.button", selectedUsages.size()));
                FindInProjectUtil.setupViewPresentation(myUsageViewPresentation, myHelper.getModel().clone());
                myUsagePreviewPanel.updateLayout(selectedUsages);
                myUsagePreviewTitle.clear();
                if (myUsagePreviewPanel.getCannotPreviewMessage(selectedUsages) == null && selectedFile != null) {
                    myUsagePreviewTitle.append(PathUtil.getFileName(selectedFile), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    VirtualFile virtualFile = VfsUtil.findFileByIoFile(new File(selectedFile), true);
                    String locationPath = virtualFile == null ? null : getPresentablePath(myProject, virtualFile.getParent(), 120);
                    if (locationPath != null) {
                        myUsagePreviewTitle.append(spaceAndThinSpace() + locationPath,
                            new SimpleTextAttributes(STYLE_PLAIN, UIUtil.getContextHelpForeground()));
                    }
                }
            });
        };
        myResultsPreviewTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || Disposer.isDisposed(myPreviewUpdater)) return;
            myPreviewUpdater.addRequest(updatePreviewRunnable, 50); //todo[vasya]: remove this dirty hack of updating preview panel after clicking on Replace button
        });
        DocumentAdapter documentAdapter = new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (myDialog == null) return;
                if (e.getDocument() == mySearchComponent.getDocument()) {
                    scheduleResultsUpdate();
                }
                if (e.getDocument() == myReplaceComponent.getDocument()) {
                    applyTo(myHelper.getModel());
                    if (myHelper.getModel().isRegularExpressions()) {
                        myComponentValidator.updateInfo(getValidationInfo(myHelper.getModel()));
                    }
                    ApplicationManager.getApplication().invokeLater(updatePreviewRunnable);
                }
            }
        };
        mySearchComponent.getDocument().addDocumentListener(documentAdapter);
        myReplaceComponent.getDocument().addDocumentListener(documentAdapter);

        mySearchRescheduleOnCancellationsAlarm = new Alarm();

        myPreviewSplitter = new OnePixelSplitter(true, .33f);
        myPreviewSplitter.setSplitterProportionKey(SPLITTER_SERVICE_KEY);
        myPreviewSplitter.getDivider().setBackground(OnePixelDivider.BACKGROUND);
        JBScrollPane scrollPane = new JBScrollPane(myResultsPreviewTable) {
            @Override
            public Dimension getMinimumSize() {
                Dimension size = super.getMinimumSize();
                size.height = myResultsPreviewTable.getPreferredScrollableViewportSize().height;
                return size;
            }
        };
        myPreviewSplitter.setFirstComponent(scrollPane);
        JPanel bottomPanel = new JPanel(new MigLayout("flowx, ins 0 0 0 0, fillx, hidemode 3, gap 0"));
        myNewTabCheckbox = new JBCheckBox(FindBundle.message("find.open.in.new.tab.checkbox"), FindSettings.getInstance().isShowResultsInSeparateView());
        myNewTabCheckbox.setOpaque(false);
        bottomPanel.add(myNewTabCheckbox);
        myNewTabCheckbox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                FindSettings.getInstance().setShowResultsInSeparateView(myNewTabCheckbox.isSelected());
            }
        });
        myOKHintLabel = new JBLabel("");
        myOKHintLabel.setEnabled(false);
        myNavigationHintLabel = new JBLabel("");
        myNavigationHintLabel.setEnabled(false);
        myNavigationHintLabel.setFont(JBUI.Fonts.smallFont());
        Insets insets = myOKButton.getInsets();
        String btnGapLeft = "gapleft " + Math.max(0, JBUIScale.scale(12) - insets.left - insets.right);

        bottomPanel.add(myNavigationHintLabel, btnGapLeft);
        bottomPanel.add(Box.createHorizontalGlue(), "growx, pushx");
        bottomPanel.add(myOKHintLabel);
        bottomPanel.add(myOKButton, btnGapLeft);
        bottomPanel.add(myReplaceAllButton, btnGapLeft);
        bottomPanel.add(myReplaceSelectedButton, btnGapLeft);

        myCodePreviewComponent = myUsagePreviewPanel.createComponent();
        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.add(myUsagePreviewTitle, BorderLayout.NORTH);
        previewPanel.add(myCodePreviewComponent, BorderLayout.CENTER);
        myPreviewSplitter.setSecondComponent(previewPanel);
        JPanel scopesPanel = new JPanel();
        new RowBuilder(scopesPanel)
            .gap(26)
            .add(myScopeSelectionToolbar.getComponent())
            .addResizable(myScopeDetailsPanel);
        setLayout(new MigLayout("flowx, ins 0, gap 0, fillx, hidemode 3"));

        myIsPinned.set(UISettings.getInstance().getPinFindInPath());

//        if (ExperimentalUI.isNewUI()) {
//            Color background = JBUI.CurrentTheme.Popup.BACKGROUND;
//            header.panel.setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()),
//                PopupUtil.getComplexPopupHorizontalHeaderBorder()));
//            header.panel.setBackground(JBUI.CurrentTheme.ComplexPopup.HEADER_BACKGROUND);
//            header.cbFileFilter.setOpaque(false);
//            setBackground(background);
//            mySearchTextArea.setOpaque(false);
//            mySearchTextArea.setBorder(PopupUtil.createComplexPopupTextFieldBorder());
//            myReplaceTextArea.setOpaque(false);
//            myReplaceTextArea.setBorder(PopupUtil.createComplexPopupTextFieldBorder());
//            scopesPanel.setOpaque(false);
//            myScopeSelectionToolbar.getComponent().setOpaque(false);
//            myScopeDetailsPanel.setOpaque(false);
//            UIUtil.setOpaqueRecursively(myScopeDetailsPanel, false);
//            myResultsPreviewTable.setBackground(background);
//            previewPanel.setBackground(background);
//            scopesPanel.setBorder(JBUI.Borders.empty(4, 20));
//            myScopeSelectionToolbar.setBorder(JBUI.Borders.empty());
//
//            Insets textFieldBorderInsets = JBUI.CurrentTheme.ComplexPopup.textFieldBorderInsets();
//            //noinspection UseDPIAwareInsets
//            myPreviewSplitter.setBlindZone(() -> new Insets(0, textFieldBorderInsets.left, 0, textFieldBorderInsets.right));
//            bottomPanel.setBorder(JBUI.Borders.empty(5, 18));
//            bottomPanel.setBackground(JBUI.CurrentTheme.Advertiser.background());
//        } else {
//            header.panel.setBorder(JBUI.Borders.empty(2, 5));
//            mySearchTextArea.setBorder(JBUI.Borders.compound(
//                JBUI.Borders.customLine(JBUI.CurrentTheme.BigPopup.searchFieldBorderColor(), 1, 0, 1, 0),
//                JBUI.Borders.empty(1, 0, 2, 0)));
//            myReplaceTextArea.setBorder(JBUI.Borders.compound(
//                JBUI.Borders.customLine(JBUI.CurrentTheme.BigPopup.searchFieldBorderColor(), 0, 0, 1, 0),
//                JBUI.Borders.empty(1, 0, 2, 0)));
//            scopesPanel.setBorder(JBUI.Borders.empty(3, 5));
//            bottomPanel.setBorder(JBUI.Borders.empty(5));
//        }
//        PopupUtil.applyPreviewTitleInsets(myUsagePreviewTitle);
        scrollPane.setBorder(JBUI.Borders.empty());
//        add(header.panel, "growx, pushx, wrap");

        add(mySearchTextArea, "pushx, growx, wrap");
        add(myReplaceTextArea, "pushx, growx, wrap");
        add(scopesPanel, "pushx, growx, ax left, wrap");
        add(myPreviewSplitter, "pushx, growx, growy, pushy, wrap");
        add(bottomPanel, "pushx, growx, dock south");

        List<Component> focusOrder = new ArrayList<>();
        focusOrder.add(mySearchComponent);
        focusOrder.add(myReplaceComponent);
        focusOrder.addAll(searchExtraButtons);
        focusOrder.addAll(replaceExtraButtons);
//        focusOrder.add(header.cbFileFilter);
        ContainerUtil.addAll(focusOrder, focusableComponents(myScopeDetailsPanel));
//        focusOrder.add(editorComponent);
        ContainerUtil.addAll(focusOrder, focusableComponents(bottomPanel));
        setFocusCycleRoot(true);
        setFocusTraversalPolicy(new ListFocusTraversalPolicy(focusOrder));

        if (SystemInfo.isMac) {
            List<JButton> principalButtons = new ArrayList<>();
            principalButtons.add(myOKButton);

            Touchbar.setButtonActions(bottomPanel, null, principalButtons, myOKButton, new DefaultActionGroup(myCaseSensitiveAction, myWholeWordsAction, myRegexAction));
        }
    }

    @Contract("_,!null,_->!null")
    static @NlsSafe String getPresentablePath(@NotNull Project project, @Nullable VirtualFile virtualFile, int maxChars) {
        if (virtualFile == null) return null;
        String path = ScratchUtil.isScratch(virtualFile)
            ? ScratchUtil.getRelativePath(project, virtualFile)
            : VfsUtilCore.isAncestor(project.getBaseDir(), virtualFile, true)
            ? VfsUtilCore.getRelativeLocation(virtualFile, project.getBaseDir())
            : FileUtil.getLocationRelativeToUserHome(virtualFile.getPath());
        return path == null ? null : maxChars < 0 ? path : StringUtil.trimMiddle(path, maxChars);
    }


    private void onFileMaskChanged() {
//        Object item = header.fileMaskField.getEditor().getItem();
//        if (item != null && !item.equals(header.fileMaskField.getSelectedItem())) {
//            header.fileMaskField.setSelectedItem(item);
//        }
        scheduleResultsUpdate();
    }

    private void closeImmediately() {
        if (canBeClosedImmediately() && myDialog != null && myDialog.isVisible()) {
            myIsPinned.set(false);
            myDialog.doCancelAction();
        }
    }

    //Some popups shown above may prevent panel closing, first of all we should close them
    private boolean canBeClosedImmediately() {
        boolean state = myIsPinned.get();
        myIsPinned.set(false);
        try {
            //Here we actually close popups
            return myDialog != null && canBeClosed();
        } finally {
            myIsPinned.set(state);
        }
    }

    private void doOK(boolean openInFindWindow) {
        if (!canBeClosedImmediately()) {
            return;
        }

        FindModel validateModel = myHelper.getModel().clone();
        applyTo(validateModel);

        ValidationInfo validationInfo = getValidationInfo(validateModel);

        if (validationInfo == null) {
            if (validateModel.isReplaceState() &&
                !openInFindWindow &&
                myResultsPreviewTable.getRowCount() > 1 &&
                !ReplaceInProjectManager.getInstance(myProject).showReplaceAllConfirmDialog(
                    myUsagesCount,
                    getStringToFind(),
                    myFilesCount,
                    getStringToReplace())) {
                return;
            }
            myHelper.getModel().copyFrom(validateModel);
            myHelper.getModel().setPromptOnReplace(openInFindWindow);
            myHelper.doOKAction();
        } else {
            String message = validationInfo.message;
            Messages.showMessageDialog(this, message, CommonBundle.getErrorTitle(), Messages.getErrorIcon());
            return;
        }
        myIsPinned.set(false);
        myDialog.doCancelAction();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        ApplicationManager.getApplication().invokeLater(() -> ScrollingUtil.ensureSelectionExists(myResultsPreviewTable), ModalityState.any());
        myScopeSelectionToolbar.updateActionsImmediately();
    }

    @Override
    public void initByModel() {
        FindModel myModel = myHelper.getModel();
        myCaseSensitiveState.set(myModel.isCaseSensitive());
        myWholeWordsState.set(myModel.isWholeWordsOnly());
        myRegexState.set(myModel.isRegularExpressions());

        mySelectedContextName = getSearchContextName(myModel);
        if (myModel.isReplaceState()) {
            myPreserveCaseState.set(myModel.isPreserveCase());
        }

//        mySelectedScope = myScopeUI.initByModel(myModel);

        boolean isThereFileFilter = myModel.getFileFilter() != null && !myModel.getFileFilter().isEmpty();
//        try {
//            header.cbFileFilter.putClientProperty("dontRequestFocus", Boolean.TRUE);
//            header.cbFileFilter.setSelected(isThereFileFilter);
//        } finally {
//            header.cbFileFilter.putClientProperty("dontRequestFocus", null);
//        }
//        header.fileMaskField.removeAllItems();
        FindSettings findSettings = FindSettings.getInstance();
        @NlsSafe String[] fileMasks = findSettings.getRecentFileMasks();
//        for (int i = fileMasks.length - 1; i >= 0; i--) {
//            header.fileMaskField.addItem(fileMasks[i]);
//        }
//        if (fileMasks.length > 0) {
//            String fileMask = findSettings.getFileMask();
//            header.fileMaskField.setSelectedItem(fileMask != null ? fileMask : fileMasks[fileMasks.length - 1]);
//        }
//        header.fileMaskField.setEnabled(isThereFileFilter);
        String toSearch = myModel.getStringToFind();
        FindInProjectSettings findInProjectSettings = FindInProjectSettings.getInstance(myProject);

        if (StringUtil.isEmpty(toSearch)) {
            String[] history = findInProjectSettings.getRecentFindStrings();
            toSearch = history.length > 0 ? history[history.length - 1] : "";
        }

        mySearchComponent.setText(toSearch);
        String toReplace = myModel.getStringToReplace();

        if (StringUtil.isEmpty(toReplace)) {
            String[] history = findInProjectSettings.getRecentReplaceStrings();
            toReplace = history.length > 0 ? history[history.length - 1] : "";
        }
        myReplaceComponent.setText(toReplace);
        updateControls();
        updateScopeDetailsPanel();

        boolean isReplaceState = myHelper.isReplaceState();
//        header.titleLabel.setText(myHelper.getTitle());
        myReplaceTextArea.setVisible(isReplaceState);
        myOKHintLabel.setText(KeymapUtil.getKeystrokeText(ENTER_WITH_MODIFIERS));
        myOKButton.setText(FindBundle.message("find.popup.find.button"));
        myReplaceAllButton.setVisible(isReplaceState);
        myReplaceSelectedButton.setVisible(isReplaceState);
    }

    private void updateControls() {
        myReplaceAllButton.setVisible(myHelper.isReplaceState());
        myReplaceSelectedButton.setVisible(myHelper.isReplaceState());
        myNavigationHintLabel.setVisible(mySearchComponent.getText().contains("\n"));
        mySearchTextArea.updateExtraActions();
        myReplaceTextArea.updateExtraActions();
        if (myNavigationHintLabel.isVisible()) {
            myNavigationHintLabel.setText("");
            KeymapManager keymapManager = KeymapManager.getInstance();
            Keymap activeKeymap = keymapManager != null ? keymapManager.getActiveKeymap() : null;
            if (activeKeymap != null) {
                String findNextText = KeymapUtil.getFirstKeyboardShortcutText("FindNext");
                String findPreviousText = KeymapUtil.getFirstKeyboardShortcutText("FindPrevious");
                if (!StringUtil.isEmpty(findNextText) && !StringUtil.isEmpty(findPreviousText)) {
                    myNavigationHintLabel.setText(FindBundle.message("label.use.0.and.1.to.select.usages", findNextText, findPreviousText));
                }
            }
        }
    }

    private void updateScopeDetailsPanel() {
        ((CardLayout) myScopeDetailsPanel.getLayout()).show(myScopeDetailsPanel, mySelectedScope.name);
        Component firstFocusableComponent = focusableComponents(myScopeDetailsPanel).find(
            c -> c.isFocusable() && c.isEnabled() && c.isShowing()
        );
        myScopeDetailsPanel.revalidate();
        myScopeDetailsPanel.repaint();
        if (firstFocusableComponent != null) {
            ApplicationManager.getApplication().invokeLater(
                () -> IdeFocusManager.getInstance(myProject).requestFocus(firstFocusableComponent, true));
        }
        if (firstFocusableComponent == null && !mySearchComponent.isFocusOwner() && !myReplaceComponent.isFocusOwner()) {
            ApplicationManager.getApplication().invokeLater(
                () -> IdeFocusManager.getInstance(myProject).requestFocus(mySearchComponent, true));
        }
    }

    public void scheduleResultsUpdate() {
        if (myDialog == null || !myDialog.isVisible()) return;
        if (mySearchRescheduleOnCancellationsAlarm == null || mySearchRescheduleOnCancellationsAlarm.isDisposed())
            return;
        updateControls();
        mySearchRescheduleOnCancellationsAlarm.cancelAllRequests();
        mySearchRescheduleOnCancellationsAlarm.addRequest(this::findSettingsChanged, 100);
    }

    private void finishPreviousPreviewSearch() {
        if (myResultsPreviewSearchProgress != null && !myResultsPreviewSearchProgress.isCanceled()) {
            myResultsPreviewSearchProgress.cancel();
        }
    }

    private void findSettingsChanged() {
        if (isShowing()) {
            ScrollingUtil.ensureSelectionExists(myResultsPreviewTable);
        }
        ModalityState state = ModalityState.current();
        finishPreviousPreviewSearch();
        mySearchRescheduleOnCancellationsAlarm.cancelAllRequests();
        applyTo(myHelper.getModel());
        FindModel findModel = new FindModel();
        findModel.copyFrom(myHelper.getModel());
        if (findModel.getStringToFind().contains("\n")) {
            findModel.setMultiline(true);
        }

        ValidationInfo result = getValidationInfo(myHelper.getModel());
        myComponentValidator.updateInfo(result);

        ProgressIndicatorBase progressIndicatorWhenSearchStarted = new ProgressIndicatorBase() {
            @Override
            public void stop() {
                super.stop();
                onStop(System.identityHashCode(this));
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (myNeedReset.compareAndSet(true, false)) { //nothing is found, let's clear previous results
                        reset();
                    }
                });
            }
        };
        myResultsPreviewSearchProgress = progressIndicatorWhenSearchStarted;
        int hash = System.identityHashCode(myResultsPreviewSearchProgress);

        // Use previously shown usage files as hint for faster search and better usage preview performance if pattern length increased
        Set<VirtualFile> filesToScanInitially = new LinkedHashSet<>();

//        if (myHelper.myPreviousModel != null && myHelper.myPreviousModel.getStringToFind().length() < myHelper.getModel().getStringToFind().length()) {
//            DefaultTableModel previousModel = (DefaultTableModel) myResultsPreviewTable.getModel();
//            for (int i = 0, len = previousModel.getRowCount(); i < len; ++i) {
//                Object value = previousModel.getValueAt(i, 0);
//                if (value instanceof FindPopupItem) {
//                    UsageInfoAdapter usage = ((FindPopupItem) value).getUsage();
//                    if (usage instanceof UsageInfo2UsageAdapter) {
//                        VirtualFile file = ((UsageInfo2UsageAdapter) usage).getFile();
//                        if (file != null) filesToScanInitially.add(file);
//                    }
//                }
//            }
//        }
//
//        myHelper.myPreviousModel = myHelper.getModel().clone();

        myReplaceAllButton.setEnabled(false);
        myReplaceSelectedButton.setEnabled(false);
        myReplaceSelectedButton.setText(FindBundle.message("find.popup.replace.selected.button", 0));

        onStart(hash);
        if (result != null && result.component != myReplaceComponent) {
            onStop(hash, result.message);
            reset();
            return;
        }

        FindInProjectExecutor projectExecutor = FindInProjectExecutor.Companion.getInstance();
//        GlobalSearchScope scope = GlobalSearchScopeUtil.toGlobalSearchScope(
//            FindInProjectUtil.getScopeFromModel(myProject, myHelper.myPreviousModel), myProject);
//        TableCellRenderer renderer = projectExecutor.createTableCellRenderer();
//        if (renderer == null) renderer = new UsageTableCellRenderer();
//        myResultsPreviewTable.getColumnModel().getColumn(0).setCellRenderer(renderer);

        AtomicInteger resultsCount = new AtomicInteger();
        AtomicInteger resultsFilesCount = new AtomicInteger();
        FindInProjectUtil.setupViewPresentation(myUsageViewPresentation, findModel);

        ProgressManager.getInstance().runProcessWithProgressAsynchronously(new Task.Backgroundable(myProject,
            FindBundle.message("find.usages.progress.title")) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                FindUsagesProcessPresentation processPresentation =
                    FindInProjectUtil.setupProcessPresentation(myProject, myUsageViewPresentation);
                ThreadLocal<String> lastUsageFileRef = new ThreadLocal<>();
//                ThreadLocal<Reference<FindPopupItem>> recentItemRef = new ThreadLocal<>();

                projectExecutor.findUsages(myProject, myResultsPreviewSearchProgress, processPresentation, findModel, filesToScanInitially, usage -> {
                    if (isCancelled()) {
                        onStop(hash);
                        return false;
                    }

                    if (resultsCount.getAndIncrement() >= ShowUsagesAction.getUsagesPageSize()) {
                        onStop(hash);
                        return false;
                    }

                    String file = lastUsageFileRef.get();
                    String usageFile = PathUtil.toSystemIndependentName(usage.getPath());
                    if (!usageFile.equals(file)) {
                        resultsFilesCount.incrementAndGet();
                        lastUsageFileRef.set(usageFile);
                    }

//                    FindPopupItem recentItem = SoftReference.dereference(recentItemRef.get());
//                    FindPopupItem newItem;
//                    boolean merged = !myHelper.isReplaceState() && recentItem != null && recentItem.getUsage().merge(usage);
//                    if (!merged) {
//                        newItem = new FindPopupItem(usage, usagePresentation(myProject, scope, usage));
//                    } else {
//                        // recompute presentation of a merged instance
//                        newItem = recentItem.withPresentation(usagePresentation(myProject, scope, recentItem.getUsage()));
//                    }
//                    recentItemRef.set(new WeakReference<>(newItem));

                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (isCancelled()) {
                            onStop(hash);
                            return;
                        }
                        myPreviewSplitter.getSecondComponent().setVisible(true);
                        DefaultTableModel model = (DefaultTableModel) myResultsPreviewTable.getModel();
//                        if (!merged) {
//                            model.addRow(new Object[]{newItem});
//                        } else {
//                            model.fireTableRowsUpdated(model.getRowCount() - 1, model.getRowCount() - 1);
//                        }
                        myCodePreviewComponent.setVisible(true);
                        if (model.getRowCount() == 1) {
                            myResultsPreviewTable.setRowSelectionInterval(0, 0);
                        }
                        int occurrences = resultsCount.get();
                        int filesWithOccurrences = resultsFilesCount.get();
                        myCodePreviewComponent.setVisible(occurrences > 0);
                        myReplaceAllButton.setEnabled(occurrences > 0);
                        myReplaceSelectedButton.setEnabled(occurrences > 0);

                        if (occurrences > 0) {
                            if (occurrences < ShowUsagesAction.getUsagesPageSize()) {
                                myUsagesCount = String.valueOf(occurrences);
                                myFilesCount = String.valueOf(filesWithOccurrences);
//                                header.infoLabel.setText(FindBundle.message("message.matches.in.files", occurrences, filesWithOccurrences));
                            } else {
                                myUsagesCount = occurrences + "+";
                                myFilesCount = filesWithOccurrences + "+";
//                                header.infoLabel.setText(FindBundle.message("message.matches.in.files.incomplete", occurrences, filesWithOccurrences));
                            }
                        } else {
//                            header.infoLabel.setText("");
                        }
                    }, state);

                    return true;
                });
            }

            @Override
            public void onCancel() {
                if (isShowing() && progressIndicatorWhenSearchStarted == myResultsPreviewSearchProgress) {
                    scheduleResultsUpdate();
                }
            }

            boolean isCancelled() {
                return progressIndicatorWhenSearchStarted != myResultsPreviewSearchProgress || progressIndicatorWhenSearchStarted.isCanceled();
            }

            @Override
            public void onFinished() {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!isCancelled()) {
                        boolean isEmpty = resultsCount.get() == 0;
                        if (isEmpty) {
                            showEmptyText(FindBundle.message("message.nothingFound"));
                        }
                    }
                    onStop(hash);
                }, state);
            }
        }, myResultsPreviewSearchProgress);
    }

    private void reset() {
        ((DefaultTableModel) myResultsPreviewTable.getModel()).getDataVector().clear();
        ((DefaultTableModel) myResultsPreviewTable.getModel()).fireTableDataChanged();
        myResultsPreviewTable.getSelectionModel().clearSelection();
        myPreviewSplitter.getSecondComponent().setVisible(false);
//        header.infoLabel.setText(null);
    }

    private void showEmptyText(@Nullable @NlsContexts.StatusText @NotNull String message) {
        StatusText emptyText = myResultsPreviewTable.getEmptyText();
        emptyText.clear();
        FindModel model = myHelper.getModel();
        boolean dotAdded = false;
        if (StringUtil.isEmpty(model.getStringToFind())) {
            emptyText.setText(FindBundle.message("message.type.search.query"));
        } else {
            emptyText.setText(message);
        }
//        if (mySelectedScope == FindPopupScopeUIImpl.DIRECTORY && !model.isWithSubdirectories()) {
//            emptyText.appendText(".");
//            dotAdded = true;
//            emptyText.appendSecondaryText(FindBundle.message("find.recursively.hint"),
//                SimpleTextAttributes.LINK_ATTRIBUTES,
//                e -> {
//                    model.setWithSubdirectories(true);
//                    scheduleResultsUpdate();
//                });
//        }
        List<Object> usedOptions = new SmartList<>();
        if (model.isCaseSensitive() && isEnabled(myCaseSensitiveAction)) {
            usedOptions.add(myCaseSensitiveAction);
        }
        if (model.isWholeWordsOnly() && isEnabled(myWholeWordsAction)) {
            usedOptions.add(myWholeWordsAction);
        }
        if (model.isRegularExpressions() && isEnabled(myRegexAction)) {
            usedOptions.add(myRegexAction);
        }
        boolean couldBeRegexp = false;
        if (mySuggestRegexHintForEmptyResults) {
            String stringToFind = model.getStringToFind();
            if (!model.isRegularExpressions() && isEnabled(myRegexAction)) {
                String regexSymbols = ".$|()[]{}^?*+\\";
                for (int i = 0; i < stringToFind.length(); i++) {
                    if (regexSymbols.indexOf(stringToFind.charAt(i)) != -1) {
                        couldBeRegexp = true;
                        break;
                    }
                }
            }
            if (couldBeRegexp) {
                try {
                    Pattern.compile(stringToFind);
                    usedOptions.add(myRegexAction);
                } catch (Exception e) {
                    couldBeRegexp = false;
                }
            }
        }
        String fileTypeMask = getFileTypeMask();
        if (fileTypeMask != null && (FindInProjectUtil.createFileMaskCondition(fileTypeMask) != Conditions.<CharSequence>alwaysTrue())) {
//            usedOptions.add(header.cbFileFilter);
        }
        if (model.isInCommentsOnly()
            || model.isInStringLiteralsOnly()
            || model.isExceptComments()
            || model.isExceptStringLiterals()
            || model.isExceptCommentsAndStringLiterals()) {
            usedOptions.add(model);
        }
        if (!usedOptions.isEmpty()) {
            if (!dotAdded) emptyText.appendText(".");
            emptyText.appendLine(" ");
            if (couldBeRegexp) {
                emptyText.appendLine(FindBundle.message("message.nothingFound.search.with.regex"), LINK_PLAIN_ATTRIBUTES, __ -> {
                    toggleOption(myRegexAction);
                    mySuggestRegexHintForEmptyResults = false;
                }).appendText(" " + KeymapUtil.getFirstKeyboardShortcutText(myRegexAction.getShortcutSet()));
            } else if (usedOptions.size() > 1) {
                emptyText.appendLine(FindBundle.message("message.nothingFound.used.options"));
                @NlsSafe StringBuilder sb = new StringBuilder();
                for (Object option : usedOptions) {
                    if (sb.length() > 0) sb.append("  ");
                    String optionText = getOptionText(option, true);
                    if (optionText == null) continue;
                    sb.append(optionText);
                }
                emptyText.appendLine(sb.toString());
                emptyText.appendLine(FindBundle.message("message.nothingFound.clearAll"), LINK_PLAIN_ATTRIBUTES,
                    __ -> resetAllFilters()).appendText(" " + getOptionText(myResetFiltersAction, true));
            } else {
                Object option = usedOptions.get(0);
                emptyText.appendLine(FindBundle.message("message.nothingFound.used.option", getOptionText(option, false)));
                emptyText.appendLine(FindBundle.message("message.nothingFound.clearOption"), LINK_PLAIN_ATTRIBUTES,
                    __ -> resetAllFilters()).appendText(" " + getOptionText(myResetFiltersAction, true));
            }
        }
    }

    private void resetAllFilters() {
//        header.cbFileFilter.setSelected(false);
        mySelectedContextName = FindInProjectUtil.getPresentableName(FindModel.SearchContext.ANY);
        myFilterContextButton.repaint();
        myCaseSensitiveState.set(false);
        myWholeWordsState.set(false);
        myRegexState.set(false);
        scheduleResultsUpdate();
    }

    private boolean isEnabled(@NotNull AnAction action) {
        Presentation presentation = new Presentation();
        action.update(new AnActionEvent(null, DataManager.getInstance().getDataContext(this), ActionPlaces.UNKNOWN, presentation, ActionManager.getInstance(), 0));
        return presentation.isEnabled();
    }

    @Nullable
    private static @NlsContexts.StatusText String getOptionText(Object option, boolean full) {
        if (option instanceof AnAction) {
            String text = ((AnAction) option).getTemplateText();
            if (text == null) text = "";
            return (text + (full ? " " + KeymapUtil.getFirstKeyboardShortcutText(((AnAction) option).getShortcutSet()) : "")).trim();
        }
        if (option instanceof JToggleButton) {
            CustomShortcutSet shortcutSet = KeymapUtil.getShortcutsForMnemonicCode(((JToggleButton) option).getMnemonic());
            return (((JToggleButton) option).getText().replace(":", "") +
                (shortcutSet != null && full ? " " + KeymapUtil.getFirstKeyboardShortcutText(shortcutSet) : "")).trim();
        }
        if (option instanceof FindModel) return FindBundle.message("message.nothingFound.context.filter");
        return null;
    }

    private void toggleOption(@NotNull Object option) {
        if (option instanceof AnAction) {
            ((AnAction) option).actionPerformed(new AnActionEvent(null, DataManager.getInstance().getDataContext(this), ActionPlaces.UNKNOWN, new Presentation(), ActionManager.getInstance(), 0));
        } else if (option instanceof JToggleButton) {
            ((JToggleButton) option).doClick();
        } else if (option instanceof FindModel) {
            mySelectedContextName = FindInProjectUtil.getPresentableName(FindModel.SearchContext.ANY);
            myFilterContextButton.repaint();
            scheduleResultsUpdate();
        }
    }

    private void onStart(int hash) {
        myNeedReset.set(true);
        myLoadingHash = hash;
//        header.loadingIcon.setIcon(AnimatedIcon.Default.INSTANCE);
        myResultsPreviewTable.getEmptyText().setText(FindBundle.message("empty.text.searching"));
    }


    private void onStop(int hash) {
        onStop(hash, FindBundle.message("message.nothingFound"));
    }

    private void onStop(int hash, @NotNull String message) {
        if (hash != myLoadingHash) {
            return;
        }
        UIUtil.invokeLaterIfNeeded(() -> {
            //noinspection HardCodedStringLiteral
            showEmptyText(message);
//            header.loadingIcon.setIcon(EmptyIcon.ICON_16);
        });
    }

    @Override
    @Nullable
    public String getFileTypeMask() {
        String mask = null;
//        if (header.cbFileFilter != null && header.cbFileFilter.isSelected()) {
//            mask = (String) header.fileMaskField.getSelectedItem();
//        }
        return mask;
    }

    @Nullable("null means OK")
    private ValidationInfo getValidationInfo(@NotNull FindModel model) {
//        ValidationInfo scopeValidationInfo = myScopeUI.validate(model, mySelectedScope);
//        if (scopeValidationInfo != null) {
//            return scopeValidationInfo;
//        }
//
//        if (!myHelper.canSearchThisString()) {
//            return new ValidationInfo(FindBundle.message("find.empty.search.text.error"), mySearchComponent);
//        }

        if (model.isRegularExpressions()) {
            String toFind = model.getStringToFind();
            Pattern pattern;
            try {
                pattern = Pattern.compile(toFind, model.isCaseSensitive() ? Pattern.MULTILINE : Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
                if (pattern.matcher("").matches() && !toFind.endsWith("$") && !toFind.startsWith("^")) {
                    return new ValidationInfo(FindBundle.message("find.empty.match.regular.expression.error"), mySearchComponent);
                }
            } catch (PatternSyntaxException e) {
                return new ValidationInfo(FindBundle.message("find.invalid.regular.expression.error", toFind, e.getDescription()),
                    mySearchComponent);
            }
            if (model.isReplaceState()) {
                if (myResultsPreviewTable.getRowCount() > 0) {
                    Object value = myResultsPreviewTable.getModel().getValueAt(0, 0);
                    if (value instanceof Usage) {
                        try {
                            // Just check
                            ReplaceInProjectManager.getInstance(myProject).replaceUsage((Usage) value, model, Collections.emptySet(), true);
                        } catch (FindManager.MalformedReplacementStringException e) {
                            return new ValidationInfo(e.getMessage(), myReplaceComponent);
                        }
                    }
                }

                try {
                    RegExReplacementBuilder.validate(pattern, getStringToReplace());
                } catch (IllegalArgumentException e) {
                    return new ValidationInfo(FindBundle.message("find.replace.invalid.replacement.string", e.getMessage()),
                        myReplaceComponent);
                }
            }
        }

        String mask = getFileTypeMask();

        if (mask != null) {
            if (mask.isEmpty()) {
//                return new ValidationInfo(FindBundle.message("find.filter.empty.file.mask.error"), header.fileMaskField);
            }

            if (mask.contains(";")) {
//                return new ValidationInfo(FindBundle.message("message.file.masks.should.be.comma.separated"), header.fileMaskField);
            }
            try {
                createFileMaskRegExp(mask);   // verify that the regexp compiles
            } catch (PatternSyntaxException ex) {
//                return new ValidationInfo(FindBundle.message("find.filter.invalid.file.mask.error", mask), header.fileMaskField);
            }
        }
        return null;
    }

    @Override
    @NotNull
    public String getStringToFind() {
        return mySearchComponent.getText();
    }

    @NotNull
    private String getStringToReplace() {
        return myReplaceComponent.getText();
    }

    private void applyTo(@NotNull FindModel model) {
        model.setCaseSensitive(myCaseSensitiveState.get());
        if (model.isReplaceState()) {
            model.setPreserveCase(myPreserveCaseState.get());
        }
        model.setWholeWordsOnly(myWholeWordsState.get());

        FindModel.SearchContext searchContext = parseSearchContext(mySelectedContextName);

        model.setSearchContext(searchContext);
        model.setRegularExpressions(myRegexState.get());
        model.setStringToFind(getStringToFind());

        if (model.isReplaceState()) {
            model.setStringToReplace(StringUtil.convertLineSeparators(getStringToReplace()));
        }

        model.setProjectScope(false);
        model.setDirectoryName(null);
        model.setModuleName(null);
        model.setCustomScopeName(null);
        model.setCustomScope(null);
        model.setCustomScope(false);
//        myScopeUI.applyTo(model, mySelectedScope);

        model.setFindAll(false);

        String mask = getFileTypeMask();
        model.setFileFilter(mask);
    }

    private void navigateToSelectedUsage(@Nullable AnActionEvent e) {
        Navigatable[] navigatables = e != null ? e.getData(CommonDataKeys.NAVIGATABLE_ARRAY) : null;
        if (navigatables != null) {
            if (canBeClosed()) {
                myDialog.doCancelAction();
            }
            OpenSourceUtil.navigate(navigatables);
            return;
        }

        Map<Integer, Usage> usages = getSelectedUsages();
        if (usages != null) {
            if (canBeClosed()) {
                myDialog.doCancelAction();
            }
            boolean first = true;
            for (Usage usage : usages.values()) {
                if (first) {
                    usage.navigate(true);
                } else {
                    usage.highlightInEditor();
                }
                first = false;
            }
        }
    }

    @Nullable
    private Map<Integer, Usage> getSelectedUsages() {
        int[] rows = myResultsPreviewTable.getSelectedRows();
        Map<Integer, Usage> result = null;
        for (int i = rows.length - 1; i >= 0; i--) {
            int row = rows[i];
            Object valueAt = myResultsPreviewTable.getModel().getValueAt(row, 0);
            if (valueAt instanceof Usage) {
                if (result == null) result = new LinkedHashMap<>();
                result.put(row, (Usage) valueAt);
            }
        }
        return result;
    }

    @NotNull
    public static ActionToolbarImpl createToolbar(AnAction @NotNull ... actions) {
        ActionToolbarImpl toolbar = (ActionToolbarImpl) ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.EDITOR_TOOLBAR, new DefaultActionGroup(actions), true);
        toolbar.setForceMinimumSize(true);
        toolbar.setTargetComponent(toolbar);
        toolbar.setLayoutPolicy(ActionToolbar.NOWRAP_LAYOUT_POLICY);
        toolbar.setBorder(JBUI.Borders.empty(3));
        return toolbar;
    }

    @NotNull
    private static ActionToolbarImpl createScopeToolbar(AnAction @NotNull ... actions) {
        ActionToolbarImpl toolbar = new ActionToolbarImpl(ActionPlaces.EDITOR_TOOLBAR, new DefaultActionGroup(actions), true) {
            @Override
            protected @NotNull ActionButton createToolbarButton(@NotNull AnAction action,
                                                                ActionButtonLook look,
                                                                @NotNull String place,
                                                                @NotNull Presentation presentation,
                                                                @NotNull Dimension minimumSize) {
                if (!ExperimentalUI.isNewUI()) {
                    return super.createToolbarButton(action, look, place, presentation, minimumSize);
                }

                ActionButtonWithText result = new ActionButtonWithText(action, presentation, place, minimumSize) {
                    @Override
                    protected Insets getMargins() {
                        return new JBInsets(4, 2, 4, 2);
                    }
                };

                result.setLook(look);
                result.setBorder(JBUI.Borders.emptyRight(4));
                result.setFont(JBFont.medium());

                return result;
            }
        };

        toolbar.setForceMinimumSize(true);
        toolbar.setTargetComponent(toolbar);
        toolbar.setLayoutPolicy(ActionToolbar.NOWRAP_LAYOUT_POLICY);

        return toolbar;
    }

    private static void applyFont(@NotNull JBFont font, Component @NotNull ... components) {
        for (Component component : components) {
            component.setFont(font);
        }
    }

    private static void createFileMaskRegExp(@NotNull String filter) throws PatternSyntaxException {
        String pattern;
        List<String> strings = StringUtil.split(filter, ",");
        if (strings.size() == 1) {
            pattern = PatternUtil.convertToRegex(filter.trim());
        } else {
            pattern = StringUtil.join(strings, s -> "(" + PatternUtil.convertToRegex(s.trim()) + ")", "|");
        }
        // just check validity
        //noinspection ResultOfMethodCallIgnored
        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    }

    @NotNull
    private static String getSearchContextName(@NotNull FindModel model) {
        String searchContext = FindBundle.message("find.context.anywhere.scope.label");
        if (model.isInCommentsOnly()) searchContext = FindBundle.message("find.context.in.comments.scope.label");
        else if (model.isInStringLiteralsOnly())
            searchContext = FindBundle.message("find.context.in.literals.scope.label");
        else if (model.isExceptStringLiterals())
            searchContext = FindBundle.message("find.context.except.literals.scope.label");
        else if (model.isExceptComments())
            searchContext = FindBundle.message("find.context.except.comments.scope.label");
        else if (model.isExceptCommentsAndStringLiterals())
            searchContext = FindBundle.message("find.context.except.comments.and.literals.scope.label");
        return searchContext;
    }

    @NotNull
    private static FindModel.SearchContext parseSearchContext(String presentableName) {
        FindModel.SearchContext searchContext = FindModel.SearchContext.ANY;
        if (FindBundle.message("find.context.in.literals.scope.label").equals(presentableName)) {
            searchContext = FindModel.SearchContext.IN_STRING_LITERALS;
        } else if (FindBundle.message("find.context.in.comments.scope.label").equals(presentableName)) {
            searchContext = FindModel.SearchContext.IN_COMMENTS;
        } else if (FindBundle.message("find.context.except.comments.scope.label").equals(presentableName)) {
            searchContext = FindModel.SearchContext.EXCEPT_COMMENTS;
        } else if (FindBundle.message("find.context.except.literals.scope.label").equals(presentableName)) {
            searchContext = FindModel.SearchContext.EXCEPT_STRING_LITERALS;
        } else if (FindBundle.message("find.context.except.comments.and.literals.scope.label").equals(presentableName)) {
            searchContext = FindModel.SearchContext.EXCEPT_COMMENTS_AND_STRING_LITERALS;
        }
        return searchContext;
    }

    @NotNull
    private static JBIterable<Component> focusableComponents(@NotNull Component component) {
        return UIUtil.uiTraverser(component)
            .bfsTraversal()
            .filter(c -> c instanceof JComboBox || c instanceof AbstractButton || c instanceof JTextComponent);
    }

    public enum ToggleOptionName {CaseSensitive, PreserveCase, WholeWords, Regex, FileFilter}

    private final class MySwitchStateToggleAction extends DumbAwareToggleAction implements TooltipLinkProvider, TooltipDescriptionProvider {
        private final ToggleOptionName myOptionName;
        private final AtomicBoolean myState;
        private final Producer<Boolean> myEnableStateProvider;
        private final TooltipLink myTooltipLink;

        private MySwitchStateToggleAction(@NotNull String message,
                                          @NotNull NativeFindPopupPanel.ToggleOptionName optionName,
                                          @NotNull Icon icon, @NotNull Icon hoveredIcon, @NotNull Icon selectedIcon,
                                          @NotNull AtomicBoolean state,
                                          @NotNull Producer<Boolean> enableStateProvider) {
            this(message, optionName, icon, hoveredIcon, selectedIcon, state, enableStateProvider, null);
        }

        private MySwitchStateToggleAction(@NotNull String message,
                                          @NotNull NativeFindPopupPanel.ToggleOptionName optionName,
                                          @NotNull Icon icon, @NotNull Icon hoveredIcon, @NotNull Icon selectedIcon,
                                          @NotNull AtomicBoolean state,
                                          @NotNull Producer<Boolean> enableStateProvider,
                                          @Nullable TooltipLink tooltipLink) {
            super(FindBundle.message(message), null, icon);
            myOptionName = optionName;
            myState = state;
            myEnableStateProvider = enableStateProvider;
            myTooltipLink = tooltipLink;
            getTemplatePresentation().setHoveredIcon(hoveredIcon);
            getTemplatePresentation().setSelectedIcon(selectedIcon);
            ShortcutSet shortcut = ActionUtil.getMnemonicAsShortcut(this);
            if (shortcut != null) {
                setShortcutSet(shortcut);
                registerCustomShortcutSet(shortcut, NativeFindPopupPanel.this);
            }
        }

        @Override
        public @Nullable TooltipLink getTooltipLink(@Nullable JComponent owner) {
            return myTooltipLink;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return myState.get();
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(myEnableStateProvider.produce());
            Toggleable.setSelected(e.getPresentation(), myState.get());
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean selected) {
//            FindUsagesCollector.CHECK_BOX_TOGGLED.log(FindUsagesCollector.FIND_IN_PATH, myOptionName, selected);
            myState.set(selected);
            if (myState == myRegexState) {
                mySuggestRegexHintForEmptyResults = false;
                if (selected) myWholeWordsState.set(false);
            }
            scheduleResultsUpdate();
        }
    }

    private class MySwitchContextToggleAction extends ToggleAction implements DumbAware {
        MySwitchContextToggleAction(@NotNull FindModel.SearchContext context) {
            super(FindInProjectUtil.getPresentableName(context));
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return Objects.equals(mySelectedContextName, getTemplatePresentation().getText());
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (state) {
                mySelectedContextName = getTemplatePresentation().getText();
                myFilterContextButton.repaint();
                scheduleResultsUpdate();
            }
        }
    }

    private class MySelectScopeToggleAction extends DumbAwareToggleAction {
        private final FindPopupScopeUI.ScopeType myScope;

        MySelectScopeToggleAction(@NotNull FindPopupScopeUI.ScopeType scope) {
            super(scope.textComputable.get(), null, scope.icon);
            getTemplatePresentation().setHoveredIcon(scope.icon);
            getTemplatePresentation().setDisabledIcon(scope.icon);
            myScope = scope;
        }

        @Override
        public boolean displayTextInToolbar() {
            return true;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return mySelectedScope == myScope;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            if (state) {
                mySelectedScope = myScope;
                myScopeSelectionToolbar.updateActionsImmediately();
                updateScopeDetailsPanel();
                scheduleResultsUpdate();
            }
        }
    }

    private class MyShowFilterPopupAction extends DumbAwareAction {
        private final PopupState<JBPopup> myPopupState = PopupState.forPopup();
        private final DefaultActionGroup mySwitchContextGroup;

        MyShowFilterPopupAction() {
            super(FindBundle.messagePointer("find.popup.show.filter.popup"), Presentation.NULL_STRING, AllIcons.General.Filter);
            KeyboardShortcut keyboardShortcut = ActionManager.getInstance().getKeyboardShortcut("ShowFilterPopup");
            if (keyboardShortcut != null) {
                setShortcutSet(new CustomShortcutSet(keyboardShortcut));
            }
            mySwitchContextGroup = new DefaultActionGroup();
            mySwitchContextGroup.add(new MySwitchContextToggleAction(FindModel.SearchContext.ANY));
            mySwitchContextGroup.add(new MySwitchContextToggleAction(FindModel.SearchContext.IN_COMMENTS));
            mySwitchContextGroup.add(new MySwitchContextToggleAction(FindModel.SearchContext.IN_STRING_LITERALS));
            mySwitchContextGroup.add(new MySwitchContextToggleAction(FindModel.SearchContext.EXCEPT_COMMENTS));
            mySwitchContextGroup.add(new MySwitchContextToggleAction(FindModel.SearchContext.EXCEPT_STRING_LITERALS));
            mySwitchContextGroup.add(new MySwitchContextToggleAction(FindModel.SearchContext.EXCEPT_COMMENTS_AND_STRING_LITERALS));
            mySwitchContextGroup.setPopup(true);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            if (e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT) == null) return;
            if (myPopupState.isRecentlyHidden()) return;

            ListPopup listPopup =
                JBPopupFactory.getInstance().createActionGroupPopup(null, mySwitchContextGroup, e.getDataContext(), false, null, 10);
            myPopupState.prepareToShow(listPopup);
            listPopup.showUnderneathOf(myFilterContextButton);
        }
    }

    private final class MyPinAction extends ToggleAction {
        private MyPinAction() {
            super(IdeBundle.messagePointer("action.ToggleAction.text.pin.window"),
                IdeBundle.messagePointer("action.ToggleAction.description.pin.window"), AllIcons.General.Pin_tab);
        }

        @Override
        public boolean isDumbAware() {
            return true;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return UISettings.getInstance().getPinFindInPath();
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            myIsPinned.set(state);
            UISettings.getInstance().setPinFindInPath(state);
            FindUsagesCollector.PIN_TOGGLED.log(state);
        }
    }

    private final class MyEnterAction extends DumbAwareAction {
        private final boolean myEnterAsOK;

        private MyEnterAction(boolean enterAsOK) {
            myEnterAsOK = enterAsOK;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(
                e.getData(CommonDataKeys.EDITOR) == null /*||
                    SwingUtilities.isDescendingFrom(e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT), header.fileMaskField)*/);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
//            if (SwingUtilities.isDescendingFrom(e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT), header.fileMaskField) &&
//                header.fileMaskField.isPopupVisible()) {
//                header.fileMaskField.hidePopup();
//                return;
//            }
//            if (myScopeUI.hideAllPopups()) {
//                return;
//            }
            if (myEnterAsOK) {
                myOkActionListener.actionPerformed(null);
            } else if (myHelper.isReplaceState()) {
                myReplaceSelectedButton.doClick();
            } else {
                navigateToSelectedUsage(null);
            }
        }
    }

    private static class JBTextAreaWithMixedAccessibleContext extends JBTextArea {

        private final AccessibleContext tableContext;

        private JBTextAreaWithMixedAccessibleContext(AccessibleContext context) {
            tableContext = context;
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new TextFieldWithListAccessibleContext(this, tableContext);
            }
            return accessibleContext;
        }
    }
}