package com.sqlsplice;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.List;

/** 让用户确认当前断点中用于 SQL 和参数的变量。 */
public final class SqlSpliceDialog extends DialogWrapper {
    private final ComboBox<DebugVariable> sqlComboBox;
    private final ComboBox<DebugVariable> argsComboBox;

    public SqlSpliceDialog(List<DebugVariable> sqlVariables, List<DebugVariable> argsVariables) {
        super(true);
        sqlComboBox = new ComboBox<>(sqlVariables.toArray(DebugVariable[]::new));
        argsComboBox = new ComboBox<>(argsVariables.toArray(DebugVariable[]::new));
        selectPreferred(sqlComboBox, "sql");
        selectPreferred(argsComboBox, "args", "arg", "param", "params");
        setTitle("Splice JDBC SQL");
        setOKButtonText("Splice and Copy");
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("SQL variable (String):"), sqlComboBox, 1, false)
                .addLabeledComponent(new JBLabel("Arguments variable (array or collection):"), argsComboBox, 1, false)
                .addComponentFillVertically(new JBLabel("Only compatible local variables from the current stack frame are shown."), 0)
                .getPanel();
    }

    public DebugVariable selectedSqlVariable() {
        return (DebugVariable) sqlComboBox.getSelectedItem();
    }

    public DebugVariable selectedArgsVariable() {
        return (DebugVariable) argsComboBox.getSelectedItem();
    }

    private static void selectPreferred(ComboBox<DebugVariable> comboBox, String... tokens) {
        for (int index = 0; index < comboBox.getItemCount(); index++) {
            DebugVariable variable = comboBox.getItemAt(index);
            String normalized = variable.name().toLowerCase(java.util.Locale.ROOT);
            for (String token : tokens) {
                if (normalized.contains(token)) {
                    comboBox.setSelectedIndex(index);
                    return;
                }
            }
        }
    }
}
