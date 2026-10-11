package com.sqlsplice;

import com.intellij.debugger.engine.JavaValue;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator.XEvaluationCallback;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink;
import com.intellij.xdebugger.frame.XFullValueEvaluator;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Java 调试暂停时，从当前栈帧取变量并在调试进程中求值生成 SQL。 */
public final class JdbcSqlSpliceAction extends AnAction implements DumbAware {
    private static final String NOTIFICATION_GROUP = "JDBC SQL Splicer";
    private static final Logger LOG = Logger.getInstance(JdbcSqlSpliceAction.class);
    private static final String DIAGNOSTIC = "JDBC SQL Splicer diagnostic";

    @Override
    public void update(@NotNull AnActionEvent event) {
        // 2025.3 的 Variables 树在打开右键菜单时可能尚未提供当前 evaluator。
        // 这里若隐藏 Action，用户就完全无法进入；实际调试状态在点击后校验并给出明确提示。
        event.getPresentation().setEnabledAndVisible(true);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
        XStackFrame stackFrame = session == null ? null : session.getCurrentStackFrame();
        if (stackFrame == null || stackFrame.getEvaluator() == null) {
            LOG.info(DIAGNOSTIC + " | action.rejected | session=" + className(session)
                    + " | frame=" + className(stackFrame)
                    + " | evaluator=" + (stackFrame == null ? "null" : className(stackFrame.getEvaluator())));
            notify(project, "The current debug frame does not support expression evaluation.", NotificationType.WARNING);
            return;
        }
        LOG.info(DIAGNOSTIC + " | action.accepted | session=" + className(session)
                + " | frame=" + className(stackFrame)
                + " | evaluator=" + className(stackFrame.getEvaluator())
                + " | source=" + sourceLocation(stackFrame));
        collectVariables(project, stackFrame, selected -> showDialog(project, stackFrame, selected));
    }

    private static void collectVariables(Project project, XStackFrame stackFrame, VariableConsumer consumer) {
        List<DebugVariable> variables = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean childrenComplete = new AtomicBoolean();
        AtomicInteger resolving = new AtomicInteger();
        LOG.info(DIAGNOSTIC + " | collect.started | frame=" + className(stackFrame));
        stackFrame.computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                LOG.info(DIAGNOSTIC + " | children.received | frame=" + className(stackFrame)
                        + " | regularChildren=" + children.size()
                        + " | topValues=" + children.getTopValues().size()
                        + " | topGroups=" + children.getTopGroups().size()
                        + " | bottomGroups=" + children.getBottomGroups().size()
                        + " | last=" + last
                        + " | regular=" + describeRegularChildren(children)
                        + " | top=" + describeNamedValues(children.getTopValues())
                        + " | topGroups=" + describeGroups(children.getTopGroups())
                        + " | bottomGroups=" + describeGroups(children.getBottomGroups()));
                // 253+ 允许调试器把优先展示的局部变量放到 topValues，而不是普通 children。
                // 这两个容器互不包含，必须分别读取；不能只依赖 size()/getName()/getValue()。
                for (XNamedValue value : children.getTopValues()) {
                    resolveChild(value.getName(), value);
                }
                for (int index = 0; index < children.size(); index++) {
                    resolveChild(children.getName(index), children.getValue(index));
                }
                if (last) {
                    childrenComplete.set(true);
                    completeWhenReady();
                }
            }

            @Override
            public void tooManyChildren(int remaining) {
                LOG.info(DIAGNOSTIC + " | children.tooMany | remaining=" + remaining);
                childrenComplete.set(true);
                completeWhenReady();
            }

            @Override
            public void setAlreadySorted(boolean alreadySorted) {
                // 局部变量的原始顺序即可。
            }

            @Override
            public void setErrorMessage(@NotNull String errorMessage) {
                LOG.warn(DIAGNOSTIC + " | children.error | message=" + errorMessage);
                childrenComplete.set(true);
                completeWhenReady();
                ApplicationManager.getApplication().invokeLater(
                        () -> JdbcSqlSpliceAction.notify(project, "Failed to read debug variables: " + errorMessage, NotificationType.ERROR));
            }

            @Override
            public void setErrorMessage(@NotNull String errorMessage, @Nullable XDebuggerTreeNodeHyperlink link) {
                setErrorMessage(errorMessage);
            }

            @Override
            public void setMessage(@NotNull String message, @Nullable Icon icon,
                                   @NotNull SimpleTextAttributes attributes, @Nullable XDebuggerTreeNodeHyperlink link) {
                LOG.info(DIAGNOSTIC + " | children.message | message=" + message);
                if (!message.isBlank()) {
                    ApplicationManager.getApplication().invokeLater(
                            () -> JdbcSqlSpliceAction.notify(project, message, NotificationType.WARNING));
                }
            }

            private void completeWhenReady() {
                if (childrenComplete.get() && resolving.get() == 0 && completed.compareAndSet(false, true)) {
                    List<DebugVariable> snapshot;
                    synchronized (variables) {
                        snapshot = List.copyOf(variables);
                    }
                    LOG.info(DIAGNOSTIC + " | collect.completed | resolved=" + describeDebugVariables(snapshot));
                    ApplicationManager.getApplication().invokeLater(() -> consumer.accept(snapshot));
                }
            }

            private void resolveChild(@Nullable String name, @Nullable XValue value) {
                // 分组/加载占位节点不代表可在表达式中引用的局部变量，直接跳过。
                if (name == null || value == null) {
                    LOG.info(DIAGNOSTIC + " | child.skipped | name=" + name + " | value=" + className(value));
                    return;
                }
                LOG.info(DIAGNOSTIC + " | child.received | name=" + name + " | valueClass=" + className(value)
                        + " | legalJavaIdentifier=" + isLegalVariableName(name));
                resolving.incrementAndGet();
                resolveVariable(name, value, variable -> {
                    if (variable != null) {
                        variables.add(variable);
                        LOG.info(DIAGNOSTIC + " | child.resolved | name=" + variable.name()
                                + " | type=" + variable.type());
                    } else {
                        LOG.info(DIAGNOSTIC + " | child.notResolved | name=" + name);
                    }
                    resolving.decrementAndGet();
                    completeWhenReady();
                });
            }
        });
    }

    private static void resolveVariable(String name, XValue value, Consumer<DebugVariable> consumer) {
        if (!isLegalVariableName(name)) {
            LOG.info(DIAGNOSTIC + " | presentation.skipped | name=" + name + " | reason=illegalJavaIdentifier");
            consumer.accept(null);
            return;
        }
        String declaredType = declaredType(value);
        LOG.info(DIAGNOSTIC + " | declaredType.observed | name=" + name + " | type=" + declaredType);
        AtomicBoolean resolved = new AtomicBoolean();
        try {
            LOG.info(DIAGNOSTIC + " | presentation.requested | name=" + name + " | valueClass=" + className(value));
            value.computePresentation(new XValueNode() {
            @Override
            public void setPresentation(@Nullable Icon icon, @Nullable String type, @NotNull String text, boolean hasChildren) {
                LOG.info(DIAGNOSTIC + " | presentation.legacyCallback | name=" + name
                        + " | type=" + type + " | hasChildren=" + hasChildren + " | textLength=" + text.length());
                complete(type, declaredType);
            }

            @Override
            public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                LOG.info(DIAGNOSTIC + " | presentation.objectCallback | name=" + name
                        + " | presentationClass=" + className(presentation) + " | type=" + presentation.getType()
                        + " | hasChildren=" + hasChildren);
                complete(presentation.getType(), declaredType);
            }

            @Override
            public void setFullValueEvaluator(@NotNull XFullValueEvaluator evaluator) {
                // 完整值只用于展示，不影响变量类型分类。
            }

            private void complete(String presentationType, String declaredType) {
                if (resolved.compareAndSet(false, true)) {
                    String runtimeType = runtimeType(value);
                    String type = firstKnownType(runtimeType, declaredType, presentationType);
                    LOG.info(DIAGNOSTIC + " | presentation.completed | name=" + name
                            + " | runtimeType=" + runtimeType + " | declaredType=" + declaredType
                            + " | presentationType=" + presentationType + " | selectedType=" + type);
                    consumer.accept(type == null ? null : new DebugVariable(name, type));
                }
            }
            }, XValuePlace.TREE);
        } catch (RuntimeException exception) {
            // 某些调试器扩展会拒绝未展开节点的展示计算；单个变量失败不能阻塞整个弹窗。
            LOG.warn(DIAGNOSTIC + " | presentation.failed | name=" + name, exception);
            consumer.accept(null);
        }
    }

    private static void showDialog(Project project, XStackFrame stackFrame, List<DebugVariable> variables) {
        List<DebugVariable> sqlVariables = variables.stream()
                .filter(variable -> isSqlType(variable.type()))
                .sorted(Comparator.comparing(DebugVariable::name))
                .toList();
        List<DebugVariable> argsVariables = variables.stream()
                .filter(variable -> isArgumentsType(variable.type()))
                .sorted(Comparator.comparing(DebugVariable::name))
                .toList();
        LOG.info(DIAGNOSTIC + " | candidates.filtered | all=" + describeDebugVariables(variables)
                + " | sql=" + describeDebugVariables(sqlVariables)
                + " | args=" + describeDebugVariables(argsVariables));
        if (sqlVariables.isEmpty() || argsVariables.isEmpty()) {
            String missing = sqlVariables.isEmpty() ? "String SQL variable" : "array or collection argument variable";
            notify(project, "No usable " + missing + " was found in the current stack frame. "
                    + "See idea.log for 'JDBC SQL Splicer variable collection'.", NotificationType.WARNING);
            return;
        }

        SqlSpliceDialog dialog = new SqlSpliceDialog(sqlVariables, argsVariables);
        if (dialog.showAndGet()) {
            evaluateAndCopy(project, stackFrame, dialog.selectedSqlVariable(), dialog.selectedArgsVariable());
        }
    }

    private static void evaluateAndCopy(Project project, XStackFrame stackFrame, DebugVariable sql, DebugVariable args) {
        XDebuggerEvaluator evaluator = stackFrame.getEvaluator();
        if (evaluator == null) {
            notify(project, "The current debugger does not support expression evaluation.", NotificationType.ERROR);
            return;
        }
        evaluator.evaluate(DebugExpressionBuilder.expression(sql.name(), args.name()), new XEvaluationCallback() {
            @Override
            public void evaluated(@NotNull XValue result) {
                LOG.info(DIAGNOSTIC + " | expression.evaluated | resultClass=" + className(result));
                readEvaluatedString(result, sqlText -> ApplicationManager.getApplication().invokeLater(() -> {
                    CopyPasteManager.getInstance().setContents(new StringSelection(sqlText));
                    LOG.info(DIAGNOSTIC + " | clipboard.updated | sqlLength=" + sqlText.length());
                    JdbcSqlSpliceAction.notify(project, "SQL has been copied to the system clipboard.", NotificationType.INFORMATION);
                }), error -> ApplicationManager.getApplication().invokeLater(() -> JdbcSqlSpliceAction.notify(project,
                        "Failed to read the SQL string result from the debugger: " + error, NotificationType.ERROR)));
            }

            @Override
            public void errorOccurred(@NotNull String errorMessage) {
                LOG.warn(DIAGNOSTIC + " | expression.failed | message=" + errorMessage);
                ApplicationManager.getApplication().invokeLater(
                        () -> JdbcSqlSpliceAction.notify(project, "Failed to splice SQL: " + errorMessage, NotificationType.ERROR));
            }
        }, null);
    }

    private static boolean isArgumentsType(String type) {
        if (type.endsWith("[]") || type.startsWith("[")) {
            return true;
        }
        String rawType = type.substring(0, type.indexOf('<') >= 0 ? type.indexOf('<') : type.length()).trim();
        return "List".equals(rawType) || "Collection".equals(rawType) || "Set".equals(rawType)
                || "Queue".equals(rawType) || "Iterable".equals(rawType)
                || rawType.startsWith("java.util.") && (rawType.contains("List") || rawType.contains("Collection")
                || rawType.contains("Set") || rawType.contains("Queue") || rawType.contains("Iterable"));
    }

    private static boolean isSqlType(String type) {
        return "String".equals(type) || "java.lang.String".equals(type) || type.endsWith(".String")
                || "StringBuilder".equals(type) || "java.lang.StringBuilder".equals(type)
                || "StringBuffer".equals(type) || "java.lang.StringBuffer".equals(type)
                || "CharSequence".equals(type) || "java.lang.CharSequence".equals(type);
    }

    private static void readEvaluatedString(XValue result, Consumer<String> success, Consumer<String> error) {
        AtomicBoolean completed = new AtomicBoolean();
        AtomicBoolean fullValueRequested = new AtomicBoolean();
        result.computePresentation(new XValueNode() {
            @Override
            public void setPresentation(@Nullable Icon icon, @Nullable String type, @NotNull String text, boolean hasChildren) {
                LOG.info(DIAGNOSTIC + " | result.legacyPresentation | type=" + type + " | textLength=" + text.length()
                        + " | collecting=" + isCollectingData(text));
                if (completeRawStringIfAvailable()) {
                    return;
                }
                useFallback(text);
            }

            @Override
            public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                StringBuilder rendered = new StringBuilder();
                presentation.renderValue(new XValuePresentation.XValueTextRenderer() {
                    @Override
                    public void renderValue(@NotNull String value) {
                        rendered.append(value);
                    }

                    @Override
                    public void renderStringValue(@NotNull String value) {
                        rendered.append(value);
                    }

                    @Override
                    public void renderNumericValue(@NotNull String value) {
                        rendered.append(value);
                    }

                    @Override
                    public void renderKeywordValue(@NotNull String value) {
                        rendered.append(value);
                    }

                    @Override
                    public void renderValue(@NotNull String value, @Nullable com.intellij.openapi.editor.colors.TextAttributesKey key) {
                        rendered.append(value);
                    }

                    @Override
                    public void renderStringValue(@NotNull String prefix, @NotNull String value, int additionalLength) {
                        rendered.append(prefix).append(value);
                    }

                    @Override
                    public void renderComment(@NotNull String comment) {
                        rendered.append(comment);
                    }

                    @Override
                    public void renderSpecialSymbol(@NotNull String symbol) {
                        rendered.append(symbol);
                    }

                    @Override
                    public void renderError(@NotNull String errorMessage) {
                        rendered.append(errorMessage);
                    }
                });
                LOG.info(DIAGNOSTIC + " | result.objectPresentation | type=" + presentation.getType()
                        + " | textLength=" + rendered.length() + " | collecting=" + isCollectingData(rendered.toString()));
                if (completeRawStringIfAvailable()) {
                    return;
                }
                useFallback(rendered.toString());
            }

            @Override
            public void setFullValueEvaluator(@NotNull XFullValueEvaluator evaluator) {
                fullValueRequested.set(true);
                LOG.info(DIAGNOSTIC + " | result.fullValueRequested");
                if (completeRawStringIfAvailable()) {
                    return;
                }
                evaluator.startEvaluation(new XFullValueEvaluator.XFullValueEvaluationCallback() {
                    @Override
                    public void evaluated(@NotNull String fullValue, java.awt.Font font) {
                        LOG.info(DIAGNOSTIC + " | result.fullValueReceived | textLength=" + fullValue.length());
                        completeSuccess(unquoteDebuggerString(fullValue));
                    }

                    @Override
                    public void errorOccurred(@NotNull String errorMessage) {
                        LOG.warn(DIAGNOSTIC + " | result.fullValueFailed | message=" + errorMessage);
                        completeError(errorMessage);
                    }
                });
            }

            private void useFallback(String text) {
                if (isCollectingData(text)) {
                    // 2025.3 会先用该占位文本通知 UI，随后通过 full-value evaluator 返回真实字符串。
                    return;
                }
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!fullValueRequested.get()) {
                        completeSuccess(unquoteDebuggerString(text));
                    }
                });
            }

            private boolean completeRawStringIfAvailable() {
                String rawValue = rawJavaString(result);
                if (rawValue == null) {
                    return false;
                }
                LOG.info(DIAGNOSTIC + " | result.rawStringReceived | textLength=" + rawValue.length());
                completeSuccess(rawValue);
                return true;
            }

            private void completeSuccess(String value) {
                if (completed.compareAndSet(false, true)) {
                    LOG.info(DIAGNOSTIC + " | result.completed | textLength=" + value.length());
                    success.accept(value);
                }
            }

            private void completeError(String message) {
                if (completed.compareAndSet(false, true)) {
                    error.accept(message);
                }
            }
        }, XValuePlace.TREE);
    }

    private static String unquoteDebuggerString(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return StringUtil.unescapeStringCharacters(value.substring(1, value.length() - 1));
        }
        return value;
    }

    private static boolean isCollectingData(String text) {
        return text.startsWith("Collecting data") || text.startsWith("Collecting Data");
    }

    private static @Nullable String rawJavaString(XValue value) {
        if (!(value instanceof JavaValue javaValue) || !javaValue.getDescriptor().isValueReady()) {
            return null;
        }
        try {
            com.sun.jdi.Value jdiValue = javaValue.getDescriptor().getValue();
            return jdiValue instanceof com.sun.jdi.StringReference stringReference ? stringReference.value() : null;
        } catch (RuntimeException exception) {
            LOG.info(DIAGNOSTIC + " | rawString.unavailable | valueClass=" + className(value)
                    + " | reason=" + exception.getClass().getName());
            return null;
        }
    }

    private static boolean isLegalVariableName(String name) {
        if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            if (!Character.isJavaIdentifierPart(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static @Nullable String declaredType(XValue value) {
        if (!(value instanceof JavaValue javaValue)) {
            return null;
        }
        try {
            String type = javaValue.getDescriptor().getDeclaredType();
            return type == null || type.isBlank() ? null : type;
        } catch (RuntimeException exception) {
            LOG.info(DIAGNOSTIC + " | declaredType.unavailable | valueClass=" + className(value)
                    + " | reason=" + exception.getClass().getName());
            return null;
        }
    }

    private static @Nullable String runtimeType(XValue value) {
        if (!(value instanceof JavaValue javaValue) || !javaValue.getDescriptor().isValueReady()) {
            return null;
        }
        try {
            com.sun.jdi.Value jdiValue = javaValue.getDescriptor().getValue();
            return jdiValue == null || jdiValue.type() == null ? null : jdiValue.type().name();
        } catch (RuntimeException exception) {
            LOG.info(DIAGNOSTIC + " | runtimeType.unavailable | valueClass=" + className(value)
                    + " | reason=" + exception.getClass().getName());
            return null;
        }
    }

    private static @Nullable String firstKnownType(@Nullable String runtimeType, @Nullable String declaredType,
                                                    @Nullable String presentationType) {
        if (isKnownType(runtimeType)) {
            return runtimeType;
        }
        if (isKnownType(declaredType)) {
            return declaredType;
        }
        return isKnownType(presentationType) ? presentationType : null;
    }

    private static boolean isKnownType(@Nullable String type) {
        return type != null && !type.isBlank() && !"<unknown>".equals(type);
    }

    private static String className(@Nullable Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String sourceLocation(XStackFrame stackFrame) {
        if (stackFrame.getSourcePosition() == null) {
            return "null";
        }
        return stackFrame.getSourcePosition().getFile().getPath() + ":" + stackFrame.getSourcePosition().getLine();
    }

    private static String describeRegularChildren(XValueChildrenList children) {
        StringBuilder description = new StringBuilder("[");
        for (int index = 0; index < children.size(); index++) {
            if (index > 0) {
                description.append(", ");
            }
            description.append(children.getName(index)).append(':').append(className(children.getValue(index)));
        }
        return description.append(']').toString();
    }

    private static String describeNamedValues(List<? extends XNamedValue> values) {
        StringBuilder description = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                description.append(", ");
            }
            XNamedValue value = values.get(index);
            description.append(value.getName()).append(':').append(className(value));
        }
        return description.append(']').toString();
    }

    private static String describeGroups(List<?> groups) {
        StringBuilder description = new StringBuilder("[");
        for (int index = 0; index < groups.size(); index++) {
            if (index > 0) {
                description.append(", ");
            }
            description.append(className(groups.get(index)));
        }
        return description.append(']').toString();
    }

    private static String describeDebugVariables(List<DebugVariable> variables) {
        StringBuilder description = new StringBuilder("[");
        for (int index = 0; index < variables.size(); index++) {
            if (index > 0) {
                description.append(", ");
            }
            DebugVariable variable = variables.get(index);
            description.append(variable.name()).append(':').append(variable.type());
        }
        return description.append(']').toString();
    }

    private static void notify(Project project, String content, NotificationType type) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(content, type)
                .notify(project);
    }

    @FunctionalInterface
    private interface VariableConsumer {
        void accept(List<DebugVariable> variables);
    }
}
