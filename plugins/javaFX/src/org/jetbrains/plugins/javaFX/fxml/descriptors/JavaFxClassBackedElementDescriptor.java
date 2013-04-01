package org.jetbrains.plugins.javaFX.fxml.descriptors;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInsight.daemon.Validator;
import com.intellij.codeInsight.daemon.impl.analysis.GenericsHighlightUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.xml.XmlAttributeImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.*;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Function;
import com.intellij.xml.XmlAttributeDescriptor;
import com.intellij.xml.XmlElementDescriptor;
import com.intellij.xml.XmlElementsGroup;
import com.intellij.xml.XmlNSDescriptor;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.javaFX.fxml.FxmlConstants;
import org.jetbrains.plugins.javaFX.fxml.JavaFxCommonClassNames;
import org.jetbrains.plugins.javaFX.fxml.JavaFxPsiUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * User: anna
 * Date: 1/9/13
 */
public class JavaFxClassBackedElementDescriptor implements XmlElementDescriptor, Validator<XmlTag> {
  private final PsiClass myPsiClass;
  private final String myName;

  public JavaFxClassBackedElementDescriptor(String name, XmlTag tag) {
    this(name, JavaFxPsiUtil.findPsiClass(name, tag));
  }

  public JavaFxClassBackedElementDescriptor(String name, PsiClass aClass) {
    myName = name;
    myPsiClass = aClass;
  }

  @Override
  public String getQualifiedName() {
    return myPsiClass != null ? myPsiClass.getQualifiedName() : getName();
  }

  @Override
  public String getDefaultName() {
    return getName();
  }

  @Override
  public XmlElementDescriptor[] getElementsDescriptors(XmlTag context) {
    if (context != null) {
      if (myPsiClass != null) {
        final List<XmlElementDescriptor> children = new ArrayList<XmlElementDescriptor>();
        collectProperties(children, new Function<PsiField, XmlElementDescriptor>() {
          @Override
          public XmlElementDescriptor fun(PsiField field) {
            return new JavaFxPropertyElementDescriptor(myPsiClass, field.getName(), false);
          }
        }, false);

        final JavaFxPropertyElementDescriptor defaultPropertyDescriptor = getDefaultPropertyDescriptor();
        if (defaultPropertyDescriptor != null) {
          Collections.addAll(children, defaultPropertyDescriptor.getElementsDescriptors(context));
        } else {
          for (String name : FxmlConstants.FX_DEFAULT_ELEMENTS) {
            children.add(new JavaFxDefaultPropertyElementDescriptor(name, null));
          }
        }

        collectStaticElementDescriptors(context, children);

        final PsiType returnType = JavaFxPsiUtil.getDefaultPropertyExpectedType(myPsiClass);
        if (returnType != null) {
          JavaFxPropertyElementDescriptor.collectDescriptorsByCollection(returnType, myPsiClass.getResolveScope(), children, myPsiClass.getProject());
        }

        if (!children.isEmpty()) {
          return children.toArray(new XmlElementDescriptor[children.size()]);
        }
      }
    }
    return XmlElementDescriptor.EMPTY_ARRAY;
  }

  private JavaFxPropertyElementDescriptor getDefaultPropertyDescriptor() {
    final PsiAnnotation defaultProperty = AnnotationUtil
      .findAnnotationInHierarchy(myPsiClass, Collections.singleton(JavaFxCommonClassNames.JAVAFX_BEANS_DEFAULT_PROPERTY));
    if (defaultProperty != null) {
      final PsiAnnotationMemberValue defaultPropertyAttributeValue = defaultProperty.findAttributeValue("value");
      if (defaultPropertyAttributeValue instanceof PsiLiteralExpression) {
        final Object value = ((PsiLiteralExpression)defaultPropertyAttributeValue).getValue();
        if (value instanceof String) {
          return new JavaFxPropertyElementDescriptor(myPsiClass, (String)value, false);
        }
      }
    }
    return null;
  }

  static void collectStaticAttributesDescriptors(@Nullable XmlTag context, List<XmlAttributeDescriptor> simpleAttrs) {
    if (context == null) return;
    collectParentStaticProperties(context.getParentTag(), simpleAttrs, new Function<PsiMethod, XmlAttributeDescriptor>() {
      @Override
      public XmlAttributeDescriptor fun(PsiMethod method) {
        return new JavaFxSetterAttributeDescriptor(method, method.getContainingClass());
      }
    });
  }

  protected static void collectStaticElementDescriptors(XmlTag context, List<XmlElementDescriptor> children) {
    collectParentStaticProperties(context, children, new Function<PsiMethod, XmlElementDescriptor>() {
      @Override
      public XmlElementDescriptor fun(PsiMethod method) {
        final PsiClass aClass = method.getContainingClass();
        return new JavaFxPropertyElementDescriptor(aClass, PropertyUtil.getPropertyName(method.getName()), true);
      }
    });
  }

  private static <T> void collectParentStaticProperties(XmlTag context, List<T> children, Function<PsiMethod, T> factory) {
    final CachedValuesManager manager = context != null ? CachedValuesManager.getManager(context.getProject()) : null;
    XmlTag tag = context;
    while (tag != null) {
      final XmlElementDescriptor descr = tag.getDescriptor();
      if (descr instanceof JavaFxClassBackedElementDescriptor) {
        final PsiElement element = descr.getDeclaration();
        if (element instanceof PsiClass) {
          final List<PsiMethod> setters = manager.getCachedValue(element, new CachedValueProvider<List<PsiMethod>>() {
            @Nullable
            @Override
            public Result<List<PsiMethod>> compute() {
              final List<PsiMethod> meths = new ArrayList<PsiMethod>();
              for (PsiMethod method : ((PsiClass)element).getAllMethods()) {
                if (method.hasModifierProperty(PsiModifier.STATIC) && method.getName().startsWith("set")) {
                  final PsiParameter[] parameters = method.getParameterList().getParameters();
                  if (parameters.length == 2 &&
                      InheritanceUtil.isInheritor(parameters[0].getType(), JavaFxCommonClassNames.JAVAFX_SCENE_NODE)) {
                    meths.add(method);
                  }
                }
              }
              return Result.create(meths, PsiModificationTracker.JAVA_STRUCTURE_MODIFICATION_COUNT);
            }
          });
          for (PsiMethod setter : setters) {
            children.add(factory.fun(setter));
          }
        }
      }
      tag = tag.getParentTag();
    }
  }

  @Nullable
  @Override
  public XmlElementDescriptor getElementDescriptor(XmlTag childTag, XmlTag contextTag) {
    final String name = childTag.getName();
    if (FxmlConstants.FX_DEFAULT_ELEMENTS.contains(name)) {
      return new JavaFxDefaultPropertyElementDescriptor(name, childTag);
    }
    final String shortName = StringUtil.getShortName(name);
    if (!name.equals(shortName)) { //static property
      final PsiMethod propertySetter = JavaFxPsiUtil.findPropertySetter(name, childTag);
      if (propertySetter != null) {
        return new JavaFxPropertyElementDescriptor(propertySetter.getContainingClass(), shortName, true);
      }

      final Project project = childTag.getProject();
      if (JavaPsiFacade.getInstance(project).findClass(name, GlobalSearchScope.allScope(project)) == null) {
        return null;
      }
    }

    final String parentTagName = contextTag.getName();
    if (myPsiClass != null) {
      if (!FxmlConstants.FX_DEFINE.equals(parentTagName)) {
        JavaFxPropertyElementDescriptor elementDescriptor = new JavaFxPropertyElementDescriptor(myPsiClass, name, false);
        if (FxmlConstants.FX_ROOT.equals(parentTagName)) {
          final PsiField fieldByName = myPsiClass.findFieldByName(name, true);
          if (fieldByName != null) {
            return elementDescriptor;
          }
        } else {
          final JavaFxPropertyElementDescriptor defaultPropertyDescriptor = getDefaultPropertyDescriptor();
          if (defaultPropertyDescriptor != null) {
            final String defaultPropertyName = defaultPropertyDescriptor.getName();
            if (StringUtil.equalsIgnoreCase(defaultPropertyName, name) && !StringUtil.equals(defaultPropertyName, name)) {
              final XmlElementDescriptor childDescriptor = defaultPropertyDescriptor.getElementDescriptor(childTag, contextTag);
              if (childDescriptor != null) {
                return childDescriptor;
              }
            }
          }
          final PsiElement declaration = elementDescriptor.getDeclaration();
          if (declaration != null) {
            if (declaration instanceof PsiField && ((PsiField)declaration).getType() instanceof PsiPrimitiveType) {
              return null;
            }
            return elementDescriptor;
          }
        }
      }
    }
    return new JavaFxClassBackedElementDescriptor(name, childTag);
  }

  @Override
  public XmlAttributeDescriptor[] getAttributesDescriptors(@Nullable XmlTag context) {
    if (context != null) {
      final String name = context.getName();
      if (Comparing.equal(name, getName()) && myPsiClass != null) {
        final List<XmlAttributeDescriptor> simpleAttrs = new ArrayList<XmlAttributeDescriptor>();
        collectInstanceProperties(simpleAttrs);
        collectStaticAttributesDescriptors(context, simpleAttrs);
        for (String defaultProperty : FxmlConstants.FX_DEFAULT_PROPERTIES) {
          simpleAttrs.add(new JavaFxDefaultAttributeDescriptor(defaultProperty, myPsiClass));
        }
        return simpleAttrs.isEmpty() ? XmlAttributeDescriptor.EMPTY : simpleAttrs.toArray(new XmlAttributeDescriptor[simpleAttrs.size()]);
      }
    }
    return XmlAttributeDescriptor.EMPTY;
  }

  protected void collectInstanceProperties(List<XmlAttributeDescriptor> simpleAttrs) {
    collectProperties(simpleAttrs, new Function<PsiField, XmlAttributeDescriptor>() {
      @Override
      public XmlAttributeDescriptor fun(PsiField field) {
        return new JavaFxPropertyAttributeDescriptor(field.getName(), myPsiClass);
      }
    }, true);
  }

  private <T> void collectProperties(final List<T> children, final Function<PsiField, T> factory, final boolean acceptPrimitive) {
    final List<PsiField> fieldList =
      CachedValuesManager.getManager(myPsiClass.getProject()).getCachedValue(myPsiClass, new CachedValueProvider<List<PsiField>>() {
        @Nullable
        @Override
        public Result<List<PsiField>> compute() {
          List<PsiField> acceptableFields = new ArrayList<PsiField>();
          final PsiField[] fields = myPsiClass.getAllFields();
          if (fields.length > 0) {
            for (PsiField field : fields) {
              if (field.hasModifierProperty(PsiModifier.STATIC)) continue;
              final PsiType fieldType = field.getType();
              if (!JavaFxPsiUtil.isReadOnly(myPsiClass, field) &&
                  InheritanceUtil.isInheritor(fieldType, JavaFxCommonClassNames.JAVAFX_BEANS_PROPERTY) ||
                  fieldType.equalsToText(CommonClassNames.JAVA_LANG_STRING) ||
                  (acceptPrimitive && fieldType instanceof PsiPrimitiveType) ||
                  GenericsHighlightUtil.getCollectionItemType(field.getType(), myPsiClass.getResolveScope()) != null) {
                acceptableFields.add(field);
              }
            }
          }
          return Result.create(acceptableFields, PsiModificationTracker.JAVA_STRUCTURE_MODIFICATION_COUNT);
        }
      });
    if (fieldList != null) {
      for (PsiField field : fieldList) {
        children.add(factory.fun(field));
      }
    }
  }

  @Nullable
  @Override
  public XmlAttributeDescriptor getAttributeDescriptor(@NonNls String attributeName, @Nullable XmlTag context) {
    if (myPsiClass == null) return null;
    if (myPsiClass.findFieldByName(attributeName, true) == null) {
      if (FxmlConstants.FX_DEFAULT_PROPERTIES.contains(attributeName)){
        return new JavaFxDefaultAttributeDescriptor(attributeName, myPsiClass);
      } else {
        final PsiMethod propertySetter = JavaFxPsiUtil.findPropertySetter(attributeName, context);
        if (propertySetter != null) {
          return new JavaFxStaticPropertyAttributeDescriptor(propertySetter, attributeName);
        }
        final PsiMethod getter = JavaFxPsiUtil.findPropertyGetter(attributeName, myPsiClass);
        if (getter != null) {
          return new JavaFxPropertyAttributeDescriptor(attributeName, myPsiClass);
        }
        return null;
      }
    }
    return new JavaFxPropertyAttributeDescriptor(attributeName, myPsiClass);
  }

  @Nullable
  @Override
  public XmlAttributeDescriptor getAttributeDescriptor(XmlAttribute attribute) {
    return getAttributeDescriptor(attribute.getName(), attribute.getParent());
  }

  @Override
  public XmlNSDescriptor getNSDescriptor() {
    return null;
  }

  @Nullable
  @Override
  public XmlElementsGroup getTopGroup() {
    return null;
  }

  @Override
  public int getContentType() {
    return CONTENT_TYPE_UNKNOWN;
  }

  @Nullable
  @Override
  public String getDefaultValue() {
    return null;
  }

  @Override
  public PsiElement getDeclaration() {
    return myPsiClass;
  }

  @Override
  public String getName(PsiElement context) {
    return getName();
  }

  @Override
  public String getName() {
    return myName;
  }

  @Override
  public void init(PsiElement element) {
  }

  @Override
  public Object[] getDependences() {
    return ArrayUtil.EMPTY_OBJECT_ARRAY;
  }

  @Override
  public void validate(@NotNull XmlTag context, @NotNull ValidationHost host) {
    final XmlTag parentTag = context.getParentTag();
    if (parentTag != null) {
      final XmlAttribute attribute = context.getAttribute(FxmlConstants.FX_CONTROLLER);
      if (attribute != null) {
        host.addMessage(((XmlAttributeImpl)attribute).getNameElement(), "fx:controller can only be applied to root element", ValidationHost.ErrorType.ERROR); //todo add delete/move to upper tag fix
      }
    }
    final String canCoerceError = JavaFxPsiUtil.isClassAcceptable(parentTag, myPsiClass);
    if (canCoerceError != null) {
      host.addMessage(context.getNavigationElement(), canCoerceError, ValidationHost.ErrorType.ERROR);
    }
    if (myPsiClass != null && myPsiClass.isValid()) {
      final String message = JavaFxPsiUtil.isAbleToInstantiate(myPsiClass);
      if (message != null) {
        host.addMessage(context, message, ValidationHost.ErrorType.ERROR);
      }
    }
  }
}
