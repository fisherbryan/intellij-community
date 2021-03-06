/*
 * Copyright 2003-2017 Dave Griffith, Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.controlflow;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ui.SingleCheckboxOptionsPanel;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.ConstantExpressionUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.util.RefactoringUtil;
import com.intellij.util.IncorrectOperationException;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.PsiReplacementUtil;
import com.siyeh.ig.psiutils.*;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

public class PointlessBooleanExpressionInspection extends BaseInspection {
  private enum BooleanExpressionKind {
    USELESS, USELESS_WITH_SIDE_EFFECTS, UNKNOWN
  }

  static final Set<IElementType> booleanTokens = new HashSet<>();
  static {
    booleanTokens.add(JavaTokenType.ANDAND);
    booleanTokens.add(JavaTokenType.AND);
    booleanTokens.add(JavaTokenType.OROR);
    booleanTokens.add(JavaTokenType.OR);
    booleanTokens.add(JavaTokenType.XOR);
    booleanTokens.add(JavaTokenType.EQEQ);
    booleanTokens.add(JavaTokenType.NE);
  }

  @SuppressWarnings("PublicField")
  public boolean m_ignoreExpressionsContainingConstants = true;

  @Override
  public JComponent createOptionsPanel() {
    return new SingleCheckboxOptionsPanel(
      InspectionGadgetsBundle.message("pointless.boolean.expression.ignore.option"), this, "m_ignoreExpressionsContainingConstants"
    );
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("pointless.boolean.expression.display.name");
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @Override
  @NotNull
  public String buildErrorString(Object... infos) {
    final String replacement = (String)infos[1];
    if (replacement.isEmpty()) {
      final PsiAssignmentExpression expression = (PsiAssignmentExpression)infos[0];
      return InspectionGadgetsBundle.message("boolean.expression.does.not.modify.problem.descriptor", expression);
    }
    return InspectionGadgetsBundle.message("boolean.expression.can.be.simplified.problem.descriptor", replacement);
  }

  StringBuilder buildSimplifiedExpression(@Nullable PsiExpression expression, StringBuilder out, CommentTracker tracker) {
    if (expression instanceof PsiAssignmentExpression) {
      buildSimplifiedAssignmentExpression((PsiAssignmentExpression)expression, out, tracker);
    }
    else if (expression instanceof PsiPolyadicExpression) {
      buildSimplifiedPolyadicExpression((PsiPolyadicExpression)expression, out, tracker);
    }
    else if (expression instanceof PsiPrefixExpression) {
      buildSimplifiedPrefixExpression((PsiPrefixExpression)expression, out, tracker);
    }
    else if (expression instanceof PsiParenthesizedExpression) {
      final PsiParenthesizedExpression parenthesizedExpression = (PsiParenthesizedExpression)expression;
      final PsiExpression expression1 = parenthesizedExpression.getExpression();
      out.append('(');
      buildSimplifiedExpression(expression1, out, tracker);
      out.append(')');
    }
    else if (expression != null) {
      out.append(tracker.markUnchanged(expression).getText());
    }
    return out;
  }

  private void buildSimplifiedPolyadicExpression(PsiPolyadicExpression expression,
                                                 StringBuilder out,
                                                 CommentTracker tracker) {
    final IElementType tokenType = expression.getOperationTokenType();
    final PsiExpression[] operands = expression.getOperands();
    final List<PsiExpression> expressions = new ArrayList<>();
    if (tokenType.equals(JavaTokenType.ANDAND) || tokenType.equals(JavaTokenType.AND)) {
      for (PsiExpression operand : operands) {
        if (evaluate(operand) == Boolean.TRUE) {
          continue;
        }
        if (evaluate(operand) == Boolean.FALSE) {
          out.append(PsiKeyword.FALSE);
          return;
        }
        expressions.add(operand);
      }
      if (expressions.isEmpty()) {
        out.append(PsiKeyword.TRUE);
        return;
      }
      buildSimplifiedExpression(expressions, tokenType.equals(JavaTokenType.ANDAND) ? "&&" : "&", false, out, tracker);
    } else if (tokenType.equals(JavaTokenType.OROR) || tokenType.equals(JavaTokenType.OR)) {
      for (PsiExpression operand : operands) {
        if (evaluate(operand) == Boolean.FALSE) {
          continue;
        }
        if (evaluate(operand) == Boolean.TRUE) {
          out.append(PsiKeyword.TRUE);
          return;
        }
        expressions.add(operand);
      }
      if (expressions.isEmpty()) {
        out.append(PsiKeyword.FALSE);
        return;
      }
      buildSimplifiedExpression(expressions, tokenType.equals(JavaTokenType.OROR) ? "||" : "|", false, out, tracker);
    }
    else if (tokenType.equals(JavaTokenType.XOR) || tokenType.equals(JavaTokenType.NE)) {
      boolean negate = false;
      for (PsiExpression operand : operands) {
        if (evaluate(operand) == Boolean.FALSE) {
          continue;
        }
        if (evaluate(operand) == Boolean.TRUE) {
          negate = !negate;
          continue;
        }
        expressions.add(operand);
      }
      if (expressions.isEmpty()) {
        if (negate) {
          out.append(PsiKeyword.TRUE);
        }
        else {
          out.append(PsiKeyword.FALSE);
        }
        return;
      }
      buildSimplifiedExpression(expressions, tokenType.equals(JavaTokenType.XOR) ? "^" : "!=", negate, out, tracker);
    }
    else if (tokenType.equals(JavaTokenType.EQEQ)) {
      boolean negate = false;
      for (PsiExpression operand : operands) {
        if (evaluate(operand) == Boolean.TRUE) {
          continue;
        }
        if (evaluate(operand) == Boolean.FALSE) {
          negate = !negate;
          continue;
        }
        expressions.add(operand);
      }
      if (expressions.isEmpty()) {
        if (negate) {
          out.append(PsiKeyword.FALSE);
        }
        else {
          out.append(PsiKeyword.TRUE);
        }
        return;
      }
      buildSimplifiedExpression(expressions, "==", negate, out, tracker);
    }
    else {
      out.append(tracker.markUnchanged(expression).getText());
    }
  }

  private void buildSimplifiedExpression(List<PsiExpression> expressions,
                                         String token,
                                         boolean negate,
                                         StringBuilder out,
                                         CommentTracker tracker) {
    if (expressions.size() == 1) {
      final PsiExpression expression = expressions.get(0);
      if (!negate) {
        out.append(tracker.markUnchanged(expression).getText());
        return;
      }
      if (ComparisonUtils.isComparison(expression)) {
        final PsiBinaryExpression binaryExpression = (PsiBinaryExpression)expression;
        final String negatedComparison = ComparisonUtils.getNegatedComparison(binaryExpression.getOperationTokenType());
        final PsiExpression lhs = binaryExpression.getLOperand();
        final PsiExpression rhs = binaryExpression.getROperand();
        assert rhs != null;
        out.append(tracker.markUnchanged(lhs).getText()).append(negatedComparison).append(tracker.markUnchanged(rhs).getText());
      }
      else {
        if (ParenthesesUtils.getPrecedence(expression) > ParenthesesUtils.PREFIX_PRECEDENCE) {
          out.append("!(").append(tracker.markUnchanged(expression).getText()).append(')');
        }
        else {
          out.append('!').append(tracker.markUnchanged(expression).getText());
        }
      }
    }
    else {
      if (negate) {
        out.append("!(");
      }
      boolean useToken = false;
      for (PsiExpression expression : expressions) {
        if (useToken) {
          out.append(token);
          final PsiElement previousSibling = expression.getPrevSibling();
          if (previousSibling instanceof PsiWhiteSpace) {
            out.append(previousSibling.getText());
          }
        }
        else {
          useToken = true;
        }
        buildSimplifiedExpression(expression, out, tracker);
        final PsiElement nextSibling = expression.getNextSibling();
        if (nextSibling instanceof PsiWhiteSpace) {
          out.append(nextSibling.getText());
        }
      }
      if (negate) {
        out.append(')');
      }
    }
  }

  private void buildSimplifiedPrefixExpression(PsiPrefixExpression expression, StringBuilder out, CommentTracker tracker) {
    final PsiJavaToken sign = expression.getOperationSign();
    final IElementType tokenType = sign.getTokenType();
    final PsiExpression operand = expression.getOperand();
    if (JavaTokenType.EXCL.equals(tokenType)) {
      final Boolean value = evaluate(operand);
      if (value == Boolean.TRUE) {
        out.append(PsiKeyword.FALSE);
        return;
      }
      else if (value == Boolean.FALSE) {
        out.append(PsiKeyword.TRUE);
        return;
      }
    }
    buildSimplifiedExpression(operand, out.append(sign.getText()), tracker);
  }

  private void buildSimplifiedAssignmentExpression(PsiAssignmentExpression expression, StringBuilder out, CommentTracker tracker) {
    final IElementType tokenType = expression.getOperationTokenType();
    if (tokenType == JavaTokenType.ANDEQ) {
      if (evaluate(expression.getRExpression()) == Boolean.TRUE) {
        if (expression.getParent() instanceof PsiExpressionStatement) {
          return;
        }
        out.append(expression.getLExpression().getText());
      }
      else {
        out.append(expression.getLExpression().getText()).append("=false");
      }
      return;
    }
    else if (tokenType == JavaTokenType.OREQ) {
      if (evaluate(expression.getRExpression()) == Boolean.FALSE) {
        if (expression.getParent() instanceof PsiExpressionStatement) {
          return;
        }
        out.append(expression.getLExpression().getText());
      }
      else {
        out.append(expression.getLExpression().getText()).append("=true");
      }
      return;
    }
    out.append(expression.getLExpression().getText()).append(expression.getOperationSign().getText());
    buildSimplifiedExpression(expression.getRExpression(), out, tracker);
  }

  @Override
  public InspectionGadgetsFix buildFix(Object... infos) {
    final String replacement = (String)infos[1];
    if (replacement.isEmpty()) {
      return new RemovePointlessBooleanExpressionFix();
    }
    boolean hasSideEffect = (boolean)infos[2];
    return new PointlessBooleanExpressionFix(hasSideEffect);
  }

  private static class RemovePointlessBooleanExpressionFix extends InspectionGadgetsFix {

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return InspectionGadgetsBundle.message("boolean.expression.remove.compound.assignment.quickfix");
    }

    @Override
    protected void doFix(Project project, ProblemDescriptor descriptor) {
      new CommentTracker().deleteAndRestoreComments(descriptor.getPsiElement().getParent());
    }
  }

  private class PointlessBooleanExpressionFix extends InspectionGadgetsFix {
    private final boolean myHasSideEffect;

    public PointlessBooleanExpressionFix(boolean hasSideEffect) {
      myHasSideEffect = hasSideEffect;
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      return myHasSideEffect
             ? InspectionGadgetsBundle.message("constant.conditional.expression.simplify.quickfix.sideEffect")
             : InspectionGadgetsBundle.message("constant.conditional.expression.simplify.quickfix");
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return InspectionGadgetsBundle.message("constant.conditional.expression.simplify.quickfix");
    }

    @Override
    public void doFix(Project project, ProblemDescriptor descriptor) throws IncorrectOperationException {
      final PsiElement element = descriptor.getPsiElement();
      if (!(element instanceof PsiExpression)) {
        return;
      }
      PsiExpression expression = (PsiExpression)element;
      CommentTracker tracker = new CommentTracker();
      String simplifiedExpression = buildSimplifiedExpression(expression, new StringBuilder(), tracker).toString();
      boolean isConstant = simplifiedExpression.equals("true") || simplifiedExpression.equals("false");
      if (isConstant) {
        expression = RefactoringUtil.ensureCodeBlock(expression);
        if (expression == null) return;
        PsiStatement anchor = PsiTreeUtil.getParentOfType(expression, PsiStatement.class);
        if (anchor == null) return;
        List<PsiExpression> sideEffects = extractSideEffects(expression);
        for (PsiExpression sideEffect : sideEffects) {
          tracker.markUnchanged(sideEffect);
        }
        PsiStatement[] statements = StatementExtractor.generateStatements(sideEffects, expression);
        if (statements.length > 0) {
          BlockUtils.addBefore(anchor, statements);
        }
      }
      PsiReplacementUtil.replaceExpression(expression, simplifiedExpression, tracker);
    }

    private List<PsiExpression> extractSideEffects(PsiExpression expression) {
      if (!(expression instanceof PsiPolyadicExpression)) {
        return Collections.emptyList();
      }
      PsiPolyadicExpression polyadicExpression = (PsiPolyadicExpression)expression;
      IElementType sign = polyadicExpression.getOperationTokenType();
      Boolean stopper = (sign == JavaTokenType.ANDAND) ? Boolean.FALSE : sign == JavaTokenType.OROR ? Boolean.TRUE : null;
      PsiExpression[] operands = polyadicExpression.getOperands();
      List<PsiExpression> sideEffects = new ArrayList<>();
      for (PsiExpression operand : operands) {
        if (stopper != null && stopper.equals(evaluate(operand))) {
          break;
        }
        sideEffects.addAll(SideEffectChecker.extractSideEffectExpressions(operand));
      }
      return sideEffects;
    }
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new PointlessBooleanExpressionVisitor();
  }

  private class PointlessBooleanExpressionVisitor extends BaseInspectionVisitor {

    @Override
    public void visitPolyadicExpression(PsiPolyadicExpression expression) {
      super.visitPolyadicExpression(expression);
      checkExpression(expression);
    }

    @Override
    public void visitPrefixExpression(PsiPrefixExpression expression) {
      super.visitPrefixExpression(expression);
      checkExpression(expression);
    }

    @Override
    public void visitAssignmentExpression(PsiAssignmentExpression expression) {
      super.visitAssignmentExpression(expression);
      checkExpression(expression);
    }

    private void checkExpression(PsiExpression expression) {
      BooleanExpressionKind kind = getExpressionKind(expression);
      if (kind == BooleanExpressionKind.UNKNOWN) {
        return;
      }
      final PsiElement parent = ParenthesesUtils.getParentSkipParentheses(expression);
      if (parent instanceof PsiExpression && getExpressionKind((PsiExpression)parent) != BooleanExpressionKind.UNKNOWN) {
        return;
      }

      String replacement = buildSimplifiedExpression(expression, new StringBuilder(), new CommentTracker()).toString();

      if (parent instanceof PsiLambdaExpression &&
          !LambdaUtil.isSafeLambdaBodyReplacement((PsiLambdaExpression)parent,
                                                  () -> JavaPsiFacade.getElementFactory(expression.getProject()).createExpressionFromText(replacement, expression))) {
        return;
      }

      registerError(expression, expression, replacement, kind == BooleanExpressionKind.USELESS_WITH_SIDE_EFFECTS);
    }

    @NotNull
    private BooleanExpressionKind getExpressionKind(PsiExpression expression) {
      if (expression instanceof PsiPrefixExpression || expression instanceof PsiAssignmentExpression) {
        return evaluate(expression) != null ? BooleanExpressionKind.USELESS : BooleanExpressionKind.UNKNOWN;
      }
      if (expression instanceof PsiPolyadicExpression) {
        final PsiPolyadicExpression polyadicExpression = (PsiPolyadicExpression)expression;
        final IElementType sign = polyadicExpression.getOperationTokenType();
        if (!booleanTokens.contains(sign)) {
          return BooleanExpressionKind.UNKNOWN;
        }
        final PsiExpression[] operands = polyadicExpression.getOperands();
        boolean containsConstant = false;
        boolean stopCheckingSideEffects = false;
        boolean sideEffectMayBeRemoved = false;
        boolean reducedToConstant = false;
        for (PsiExpression operand : operands) {
          if (operand == null) {
            return BooleanExpressionKind.UNKNOWN;
          }
          final PsiType type = operand.getType();
          if (type == null || !type.equals(PsiType.BOOLEAN) && !type.equalsToText(CommonClassNames.JAVA_LANG_BOOLEAN)) {
            return BooleanExpressionKind.UNKNOWN;
          }
          if (!stopCheckingSideEffects && SideEffectChecker.mayHaveSideEffects(operand)) {
            sideEffectMayBeRemoved = true;
          }
          Boolean value = evaluate(operand);
          if (value != null) {
            containsConstant = true;
            if ((JavaTokenType.ANDAND.equals(sign) && !value) || (JavaTokenType.OROR.equals(sign) && value)) {
              stopCheckingSideEffects = true;
              reducedToConstant = true;
            }
            if ((JavaTokenType.AND.equals(sign) && !value) || (JavaTokenType.OR.equals(sign) && value)) {
              reducedToConstant = true;
            }
          }
        }
        if (containsConstant) {
          if (sideEffectMayBeRemoved && reducedToConstant) {
            return ControlFlowUtils.canExtractStatement(expression)
                   ? BooleanExpressionKind.USELESS_WITH_SIDE_EFFECTS
                   : BooleanExpressionKind.UNKNOWN;
          }
          return BooleanExpressionKind.USELESS;
        }
      }
      return BooleanExpressionKind.UNKNOWN;
    }
  }

  @Nullable
  Boolean evaluate(@Nullable PsiExpression expression) {
    if (expression == null || m_ignoreExpressionsContainingConstants && containsReference(expression)) {
      return null;
    }
    if (expression instanceof PsiParenthesizedExpression) {
      final PsiParenthesizedExpression parenthesizedExpression = (PsiParenthesizedExpression)expression;
      return evaluate(parenthesizedExpression.getExpression());
    }
    if (expression instanceof PsiPolyadicExpression) {
      final PsiPolyadicExpression polyadicExpression = (PsiPolyadicExpression)expression;
      final IElementType tokenType = polyadicExpression.getOperationTokenType();
      if (tokenType.equals(JavaTokenType.OROR)) {
        final PsiExpression[] operands = polyadicExpression.getOperands();
        for (PsiExpression operand : operands) {
          if (SideEffectChecker.mayHaveSideEffects(operand)) {
            return null;
          }
          if (evaluate(operand) == Boolean.TRUE) {
            return Boolean.TRUE;
          }
        }
      }
      else if (tokenType.equals(JavaTokenType.ANDAND)) {
        final PsiExpression[] operands = polyadicExpression.getOperands();
        for (PsiExpression operand : operands) {
          if (SideEffectChecker.mayHaveSideEffects(operand)) {
            return null;
          }
          if (evaluate(operand) == Boolean.FALSE) {
            return Boolean.FALSE;
          }
        }
      }
    }
    else if (expression instanceof PsiPrefixExpression) {
      final PsiPrefixExpression prefixExpression = (PsiPrefixExpression)expression;
      final IElementType tokenType = prefixExpression.getOperationTokenType();
      if (JavaTokenType.EXCL.equals(tokenType)) {
        final PsiExpression operand = prefixExpression.getOperand();
        final Boolean b = evaluate(operand);
        if (b == Boolean.FALSE) {
          return Boolean.TRUE;
        } else if (b == Boolean.TRUE) {
          return Boolean.FALSE;
        }
      }
    }
    else if (expression instanceof PsiAssignmentExpression) {
      final PsiAssignmentExpression assignmentExpression = (PsiAssignmentExpression)expression;
      final IElementType tokenType = assignmentExpression.getOperationTokenType();
      final PsiExpression rhs = assignmentExpression.getRExpression();
      if (JavaTokenType.ANDEQ.equals(tokenType) || JavaTokenType.OREQ.equals(tokenType)) {
        return evaluate(rhs);
      }
    }
    return (Boolean)ConstantExpressionUtil.computeCastTo(expression, PsiType.BOOLEAN);
  }

  private static boolean containsReference(@Nullable PsiExpression expression) {
    if (expression == null) {
      return false;
    }
    final ReferenceVisitor visitor = new ReferenceVisitor();
    expression.accept(visitor);
    return visitor.containsReference();
  }

  private static class ReferenceVisitor extends JavaRecursiveElementWalkingVisitor {

    private boolean referenceFound;

    @Override
    public void visitElement(PsiElement element) {
      if (referenceFound) {
        return;
      }
      super.visitElement(element);
    }

    @Override
    public void visitReferenceExpression(PsiReferenceExpression expression) {
      final PsiElement target = expression.resolve();
      if (target instanceof PsiField && ExpressionUtils.isConstant((PsiField)target)) {
        referenceFound = true;
      }
      else {
        super.visitReferenceExpression(expression);
      }
    }

    boolean containsReference() {
      return referenceFound;
    }
  }
}