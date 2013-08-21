/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.xml.util;

import com.intellij.codeInsight.daemon.EmptyResolveMessageProvider;
import com.intellij.codeInsight.daemon.XmlErrorMessages;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.util.ArrayUtil;
import com.intellij.xml.XmlAttributeDescriptor;
import com.intellij.xml.impl.BasicXmlAttributeDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
* @author Dmitry Avdeev
*         Date: 16.08.13
*/
public class XmlAttributeValueReference extends PsiReferenceBase<XmlAttributeValue> implements EmptyResolveMessageProvider {
  private final XmlAttributeDescriptor myDescriptor;

  public XmlAttributeValueReference(XmlAttributeValue value, XmlAttributeDescriptor descriptor) {
    super(value);
    myDescriptor = descriptor;
  }

  public XmlAttributeValueReference(XmlAttributeValue element,
                                    TextRange range,
                                    XmlAttributeDescriptor descriptor) {
    super(element, range);
    myDescriptor = descriptor;
  }

  @Nullable
  @Override
  public PsiElement resolve() {
    return ((BasicXmlAttributeDescriptor)myDescriptor).getValueDeclaration(getElement(), getValue());
  }

  @NotNull
  @Override
  public Object[] getVariants() {
    if (myDescriptor.isFixed()) {
      String defaultValue = myDescriptor.getDefaultValue();
      return defaultValue == null ? ArrayUtil.EMPTY_OBJECT_ARRAY : new Object[] {defaultValue};
    }
    else {
      String[] values = myDescriptor.getEnumeratedValues();
      return values == null ? ArrayUtil.EMPTY_OBJECT_ARRAY : values;
    }
  }

  @NotNull
  @Override
  public String getUnresolvedMessagePattern() {
    return myDescriptor.isFixed()
           ? XmlErrorMessages.message("attribute.should.have.fixed.value", myDescriptor.getDefaultValue())
           : XmlErrorMessages.message("wrong.attribute.value");
  }
}