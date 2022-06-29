package com.sourcegraph.browser;

import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBPanelWithEmptyText;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.StatusText;
import com.sourcegraph.config.SettingsConfigurable;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

import static com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN;

/**
 * Inspired by <a href="https://sourcegraph.com/github.com/JetBrains/intellij-community/-/blob/platform/lang-impl/src/com/intellij/find/impl/FindPopupPanel.java">FindPopupPanel.java</a>
 */
public class BrowserAndLoadingPanel extends JLayeredPane {
    private final Project project;
    private final JBPanelWithEmptyText overlayPanel;
    private boolean isBrowserVisible = false;
    private final JBPanelWithEmptyText jcefPanel;

    public BrowserAndLoadingPanel(Project project) {
        this.project = project;
        jcefPanel = new JBPanelWithEmptyText(new BorderLayout()).withEmptyText(
            "Unfortunately, the browser is not available on your system. Try running the IDE with the default OpenJDK.");

        overlayPanel = new JBPanelWithEmptyText();
        setLoading(true);

        add(overlayPanel, 0);
        add(jcefPanel, 1);
    }

    public void setBrowser(@NotNull SourcegraphJBCefBrowser browser) {
        jcefPanel.add(browser.getComponent());
    }

    public void setLoading(boolean isLoading) {
        StatusText emptyText = overlayPanel.getEmptyText();

        if (isLoading) {
            emptyText.setText("Loading...");
        } else {
            emptyText.setText("Could not connect to Sourcegraph.");
            emptyText.appendLine("Make sure your Sourcegraph URL and access token are correct to use search.");
            emptyText.appendLine("Click here to configure your Sourcegraph settings.",
                new SimpleTextAttributes(STYLE_PLAIN, JBUI.CurrentTheme.Link.Foreground.ENABLED),
                __ -> ShowSettingsUtil.getInstance().showSettingsDialog(project, SettingsConfigurable.class)
            );
        }
    }

    @Override
    public void doLayout() {
        Component overlay = getComponent(0);
        Component browser = getComponent(1);
        if (isBrowserVisible) {
            browser.setBounds(0, 0, getWidth(), getHeight());
        } else {
            browser.setBounds(0, 0, 1, 1);
        }
        overlay.setBounds(0, 0, getWidth(), getHeight());
    }

    @Override
    public Dimension getPreferredSize() {
        return getBounds().getSize();
    }

    public void setBrowserVisible(boolean browserVisible) {
        isBrowserVisible = browserVisible;
        revalidate();
        repaint();
    }
}
