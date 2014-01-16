/*
 * Copyright (c) 2010-2013 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.midpoint.web.component.data.column;

import com.evolveum.midpoint.web.component.data.DoubleButtonPanel;
import com.evolveum.midpoint.web.component.util.SimplePanel;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.extensions.markup.html.repeater.data.grid.ICellPopulator;
import org.apache.wicket.extensions.markup.html.repeater.data.table.AbstractColumn;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.model.IModel;

import java.io.Serializable;

/**
 *  @author shood
 */
public class DoubleButtonColumn<T extends Serializable>  extends AbstractColumn<T, String>{

    public static final String BUTTON_BASE_CLASS = "btn";

    public enum BUTTON_COLOR_CLASS{
        DEFAULT("btn-default"), PRIMARY("btn-primary"), SUCCESS("btn-success"),
        INFO("btn-info"), WARNING("btn-warning"), DANGER("btn-danger");

        private final String stringValue;

        private BUTTON_COLOR_CLASS(final String s){stringValue = s;}
        public String toString(){return stringValue;}
    }

    public enum BUTTON_SIZE_CLASS{
        LARGE("btn-lg"), DEFAULT(""), SMALL("btn-sm"), EXTRA_SMALL("btn-xs");

        private final String stringValue;

        private BUTTON_SIZE_CLASS(final String s){stringValue = s;}
        public String toString(){return stringValue;}
    }

    private String firstCaption;
    private String secondCaption;

    private String propertyExpression;

    public DoubleButtonColumn(IModel<String> displayModel, String propertyExpression){
        super(displayModel);
        this.propertyExpression = propertyExpression;
    }

    @Override
    public void populateItem(final Item<ICellPopulator<T>> cellItem, String componentId,
                             final IModel<T> rowModel){

        cellItem.add(new DoubleButtonPanel<T>(componentId, rowModel){

            @Override
            public String getFirstCssSizeClass(){
                return getFirstSizeCssClass();
            }

            @Override
            public String getSecondCssSizeClass(){
                return getSecondSizeCssClass();
            }

            @Override
            public String getFirstCssColorClass(){
                return getFirstColorCssClass();
            }

            @Override
            public String getSecondCssColorClass(){
                return getSecondColorCssClass();
            }

            @Override
            public String getFirstCaption(){
                return getFirstCap();
            }

            @Override
            public String getSecondCaption(){
                return getSecondCap();
            }

            @Override
            public void firstPerformed(AjaxRequestTarget target, IModel<T> model){
                firstClicked(target, model);
            }

            @Override
            public void secondPerformed(AjaxRequestTarget target, IModel<T> model){
                secondClicked(target, model);
            }
        });
    }

    public void firstClicked(AjaxRequestTarget target, IModel<T> model){}
    public void secondClicked(AjaxRequestTarget target, IModel<T> model){};

    public String getFirstSizeCssClass(){
        return DoubleButtonColumn.BUTTON_SIZE_CLASS.DEFAULT.toString();
    }

    public String getSecondSizeCssClass(){
        return DoubleButtonColumn.BUTTON_SIZE_CLASS.DEFAULT.toString();
    }

    public String getFirstColorCssClass(){
        return DoubleButtonColumn.BUTTON_COLOR_CLASS.DEFAULT.toString();
    }

    public String getSecondColorCssClass(){
        return DoubleButtonColumn.BUTTON_COLOR_CLASS.DEFAULT.toString();
    }

    public String getFirstCap(){
        return firstCaption;
    }

    public String getSecondCap(){
        return secondCaption;
    }
}