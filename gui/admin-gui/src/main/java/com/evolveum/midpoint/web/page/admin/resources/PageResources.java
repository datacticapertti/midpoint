/*
 * Copyright (c) 2010-2015 Evolveum
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

package com.evolveum.midpoint.web.page.admin.resources;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.evolveum.midpoint.web.component.dialog.ConfirmationPanel;
import com.evolveum.midpoint.web.component.search.*;
import com.evolveum.midpoint.web.session.PageStorage;
import com.evolveum.midpoint.web.session.SessionStorage;
import com.evolveum.midpoint.xml.ns._public.common.common_3.UserType;
import org.apache.commons.lang.StringUtils;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.extensions.markup.html.repeater.data.table.IColumn;
import org.apache.wicket.extensions.markup.html.repeater.data.table.PropertyColumn;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.model.AbstractReadOnlyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.request.mapper.parameter.PageParameters;

import com.evolveum.midpoint.gui.api.GuiStyleConstants;
import com.evolveum.midpoint.gui.api.component.MainObjectListPanel;
import com.evolveum.midpoint.gui.api.model.LoadableModel;
import com.evolveum.midpoint.gui.api.page.PageBase;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.RetrieveOption;
import com.evolveum.midpoint.schema.SelectorOptions;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.result.OperationResultStatus;
import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.web.application.AuthorizationAction;
import com.evolveum.midpoint.web.application.PageDescriptor;
import com.evolveum.midpoint.web.component.data.Table;
import com.evolveum.midpoint.web.component.data.column.ColumnMenuAction;
import com.evolveum.midpoint.web.component.data.column.InlineMenuHeaderColumn;
import com.evolveum.midpoint.web.component.menu.cog.InlineMenuItem;
import com.evolveum.midpoint.web.component.util.SelectableBean;
import com.evolveum.midpoint.web.page.admin.configuration.PageDebugView;
import com.evolveum.midpoint.web.page.admin.configuration.component.HeaderMenuAction;
import com.evolveum.midpoint.web.session.ResourcesStorage;
import com.evolveum.midpoint.web.session.UserProfileStorage.TableId;
import com.evolveum.midpoint.web.util.OnePageParameterEncoder;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ResourceType;

/**
 * @author lazyman
 */
@PageDescriptor(url = "/admin/resources", action = {
		@AuthorizationAction(actionUri = PageAdminResources.AUTH_RESOURCE_ALL, label = PageAdminResources.AUTH_RESOURCE_ALL_LABEL, description = PageAdminResources.AUTH_RESOURCE_ALL_DESCRIPTION),
		@AuthorizationAction(actionUri = AuthorizationConstants.AUTZ_UI_RESOURCES_URL, label = "PageResources.auth.resources.label", description = "PageResources.auth.resources.description") })
public class PageResources extends PageAdminResources {

	private static final long serialVersionUID = 1L;
	private static final Trace LOGGER = TraceManager.getTrace(PageResources.class);
	private static final String DOT_CLASS = PageResources.class.getName() + ".";
	private static final String OPERATION_TEST_RESOURCE = DOT_CLASS + "testResource";
	private static final String OPERATION_DELETE_RESOURCES = DOT_CLASS + "deleteResources";

	private static final String ID_MAIN_FORM = "mainForm";
	private static final String ID_TABLE = "table";
	private static final String ID_CONNECTOR_TABLE = "connectorTable";

	private IModel<Search> searchModel;
	private ResourceType singleDelete;

	public PageResources() {
		this(true);
	}

	public PageResources(boolean clearSessionPaging) {
		this(clearSessionPaging, "");
	}

	public PageResources(String searchText) {
		this(true, searchText);
	}

	public PageResources(boolean clearSessionPaging, String searchText) {
		searchModel = new LoadableModel<Search>(false) {

			private static final long serialVersionUID = 1L;
			@Override
			protected Search load() {
				ResourcesStorage storage = getSessionStorage().getResources();
				Search dto = storage.getSearch();

				if (dto == null) {
					dto = SearchFactory.createSearch(ResourceType.class, getPrismContext(),
							getModelInteractionService());
				}

				return dto;
			}
		};

        if (StringUtils.isNotEmpty(searchText)){
            initSearch(searchText);
        }
        initLayout();
	}

    private void initSearch(String text){
        PageStorage storage = getSessionStorage().getPageStorageMap().get(SessionStorage.KEY_RESOURCES);
        if (storage == null) {
            storage = getSessionStorage().initPageStorage(SessionStorage.KEY_RESOURCES);
        }
        Search search = SearchFactory.createSearch(UserType.class, getPrismContext(), getModelInteractionService());

        if (search.getItems() != null && search.getItems().size() > 0){
            SearchItem searchItem = search.getItems().get(0);
            searchItem.getValues().add(new SearchValue<>(text));
        }
        storage.setSearch(search);
        getSessionStorage().getPageStorageMap().put(SessionStorage.KEY_RESOURCES, storage);
    }

	private void initLayout() {
		Form mainForm = new Form(ID_MAIN_FORM);
		add(mainForm);

		Collection<SelectorOptions<GetOperationOptions>> options = SelectorOptions
				.createCollection(GetOperationOptions.createNoFetch());
		options.add(SelectorOptions.create(ResourceType.F_CONNECTOR_REF,
				GetOperationOptions.createRetrieve(RetrieveOption.INCLUDE)));

		MainObjectListPanel<ResourceType> resourceListPanel = new MainObjectListPanel<ResourceType>(ID_TABLE,
				ResourceType.class, TableId.TABLE_RESOURCES, options, this) {
			private static final long serialVersionUID = 1L;

			@Override
			protected List<InlineMenuItem> createInlineMenu() {
				return PageResources.this.createRowMenuItems();
			}

			@Override
			protected List<IColumn<SelectableBean<ResourceType>, String>> createColumns() {
				return PageResources.this.initResourceColumns();
			}

			@Override
			protected void objectDetailsPerformed(AjaxRequestTarget target, ResourceType object) {
				PageResources.this.resourceDetailsPerformed(target, object.getOid());

			}

			@Override
			protected void newObjectPerformed(AjaxRequestTarget target) {
				setResponsePage(PageResourceWizard.class);

			}
		};
		resourceListPanel.setOutputMarkupId(true);
		resourceListPanel.setAdditionalBoxCssClasses(GuiStyleConstants.CLASS_OBJECT_RESOURCE_BOX_CSS_CLASSES);
		mainForm.add(resourceListPanel);

	}

	private List<InlineMenuItem> createRowMenuItems() {

		List<InlineMenuItem> menuItems = new ArrayList<>();

		menuItems.add(new InlineMenuItem(createStringResource("PageResources.inlineMenuItem.test"),
				new ColumnMenuAction<SelectableBean<ResourceType>>() {

					@Override
					public void onClick(AjaxRequestTarget target) {
						SelectableBean<ResourceType> rowDto = getRowModel().getObject();
						testResourcePerformed(target, rowDto.getValue());
					}
				}));

		menuItems.add(new InlineMenuItem(createStringResource("PageBase.button.delete"),
				new ColumnMenuAction<SelectableBean<ResourceType>>() {

					@Override
					public void onClick(AjaxRequestTarget target) {
						SelectableBean<ResourceType> rowDto = getRowModel().getObject();
						deleteResourcePerformed(target, rowDto.getValue());
					}
				}));

		menuItems.add(new InlineMenuItem(createStringResource("pageResources.inlineMenuItem.deleteSyncToken"),
				new ColumnMenuAction<SelectableBean<ResourceType>>() {

					@Override
					public void onClick(AjaxRequestTarget target) {
						SelectableBean<ResourceType> rowDto = getRowModel().getObject();
						deleteResourceSyncTokenPerformed(target, rowDto.getValue());
					}

				}));

		menuItems.add(new InlineMenuItem(createStringResource("pageResources.inlineMenuItem.editResource"),
				new ColumnMenuAction<SelectableBean<ResourceType>>() {

					@Override
					public void onClick(AjaxRequestTarget target) {
						SelectableBean<ResourceType> rowDto = getRowModel().getObject();
						editResourcePerformed(rowDto.getValue());
					}
				}));
		menuItems.add(new InlineMenuItem(createStringResource("pageResources.button.editAsXml"),
				new ColumnMenuAction<SelectableBean<ResourceType>>() {

					@Override
					public void onClick(AjaxRequestTarget target) {
						SelectableBean<ResourceType> rowDto = getRowModel().getObject();
						editAsXmlPerformed(rowDto.getValue());
					}
				}));

		return menuItems;
	}

	private List<IColumn<SelectableBean<ResourceType>, String>> initResourceColumns() {
		List<IColumn<SelectableBean<ResourceType>, String>> columns = new ArrayList<>();

		columns.add(new PropertyColumn(createStringResource("pageResources.connectorType"),
				SelectableBean.F_VALUE + ".connector.connectorType"));
		columns.add(new PropertyColumn(createStringResource("pageResources.version"),
				SelectableBean.F_VALUE + ".connector.connectorVersion"));

		InlineMenuHeaderColumn menu = new InlineMenuHeaderColumn(initInlineMenu());
		columns.add(menu);

		return columns;
	}

	private List<InlineMenuItem> initInlineMenu() {
		List<InlineMenuItem> headerMenuItems = new ArrayList<>();
		headerMenuItems.add(new InlineMenuItem(createStringResource("PageBase.button.delete"),
				new HeaderMenuAction(this) {

					@Override
					public void onClick(AjaxRequestTarget target) {
						deleteResourcePerformed(target, null);
					}
				}));

		return headerMenuItems;
	}

	private void resourceDetailsPerformed(AjaxRequestTarget target, String oid) {
        clearSessionStorageForResourcePage();

        PageParameters parameters = new PageParameters();
		parameters.add(OnePageParameterEncoder.PARAMETER, oid);
		setResponsePage(PageResource.class, parameters);
	}

	private List<ResourceType> isAnyResourceSelected(AjaxRequestTarget target, ResourceType single) {
		List<ResourceType> selected = null;
		if (single != null) {
			selected = new ArrayList<>(1);
			selected.add(single);
			return selected;
		}
		selected = getResourceTable().getSelectedObjects();
		if (selected.size() < 1) {
			warn(createStringResource("pageResources.message.noResourceSelected"));
		}
		return selected;

	}

	private void deleteResourcePerformed(AjaxRequestTarget target, ResourceType single) {
		List<ResourceType> selected = isAnyResourceSelected(target, single);
		singleDelete = single;

		if (selected.isEmpty()) {
			return;
		}

        ConfirmationPanel dialog = new ConfirmationPanel(((PageBase)getPage()).getMainPopupBodyId(),
                createDeleteConfirmString("pageResources.message.deleteResourceConfirm",
                        "pageResources.message.deleteResourcesConfirm", true)){
            @Override
            public void yesPerformed(AjaxRequestTarget target) {
                ((PageBase)getPage()).hideMainPopup(target);
                deleteResourceConfirmedPerformed(target);
            }
        };
        ((PageBase)getPage()).showMainPopup(dialog, target);
    }

	private MainObjectListPanel<ResourceType> getResourceTable() {
		return (MainObjectListPanel<ResourceType>) get(createComponentPath(ID_MAIN_FORM, ID_TABLE));
	}

	private Table getConnectorHostTable() {
		return (Table) get(createComponentPath(ID_MAIN_FORM, ID_CONNECTOR_TABLE));
	}

	/**
	 * @param oneDeleteKey
	 *            message if deleting one item
	 * @param moreDeleteKey
	 *            message if deleting more items
	 * @param resources
	 *            if true selecting resources if false selecting from hosts
	 */
	private IModel<String> createDeleteConfirmString(final String oneDeleteKey, final String moreDeleteKey,
			final boolean resources) {
		return new AbstractReadOnlyModel<String>() {

			@Override
			public String getObject() {
				List selected = new ArrayList();
					if (singleDelete != null) {
						selected.add(singleDelete);
					} else {
						selected = getResourceTable().getSelectedObjects();
					}
				
				switch (selected.size()) {
					case 1:
						Object first = selected.get(0);
						String name = WebComponentUtil.getName(((ResourceType) first));
						return createStringResource(oneDeleteKey, name).getString();
					default:
						return createStringResource(moreDeleteKey, selected.size()).getString();
				}
			}
		};
	}

	private void deleteResourceConfirmedPerformed(AjaxRequestTarget target) {
		List<ResourceType> selected = new ArrayList<>();

		if (singleDelete != null) {
			selected.add(singleDelete);
		} else {
			selected = getResourceTable().getSelectedObjects();
		}

		OperationResult result = new OperationResult(OPERATION_DELETE_RESOURCES);
		for (ResourceType resource : selected) {
			try {
				Task task = createSimpleTask(OPERATION_DELETE_RESOURCES);

				ObjectDelta<ResourceType> delta = ObjectDelta.createDeleteDelta(ResourceType.class,
						resource.getOid(), getPrismContext());
				getModelService().executeChanges(WebComponentUtil.createDeltaCollection(delta), null, task,
						result);
			} catch (Exception ex) {
				result.recordPartialError("Couldn't delete resource.", ex);
				LoggingUtils.logUnexpectedException(LOGGER, "Couldn't delete resource", ex);
			}
		}

		result.recomputeStatus();
		if (result.isSuccess()) {
			result.recordStatus(OperationResultStatus.SUCCESS,
					"The resource(s) have been successfully deleted.");
		}

		getResourceTable().clearCache();

		showResult(result);
		target.add(getFeedbackPanel(), (Component) getResourceTable());
	}

	private void testResourcePerformed(AjaxRequestTarget target, ResourceType resourceType) {

		OperationResult result = new OperationResult(OPERATION_TEST_RESOURCE);

		// SelectableBean<ResourceType> dto = rowModel.getObject();
		// ResourceType resourceType = dto.getValue();
		if (StringUtils.isEmpty(resourceType.getOid())) {
			result.recordFatalError("Resource oid not defined in request");
		}

		Task task = createSimpleTask(OPERATION_TEST_RESOURCE);
		try {
			result = getModelService().testResource(resourceType.getOid(), task);
			// ResourceController.updateResourceState(resourceType.getState(),
			// result);

			// todo de-duplicate code (see the same operation in PageResource)
			// this provides some additional tests, namely a test for schema
			// handling section
			getModelService().getObject(ResourceType.class, resourceType.getOid(), null, task, result);
		} catch (Exception ex) {
			result.recordFatalError("Failed to test resource connection", ex);
		}

		// a bit of hack: result of TestConnection contains a result of
		// getObject as a subresult
		// so in case of TestConnection succeeding we recompute the result to
		// show any (potential) getObject problems
		if (result.isSuccess()) {
			result.recomputeStatus();
		}

		// if (!result.isSuccess()) {
		showResult(result);
		target.add(getFeedbackPanel());
		// }
		target.add(getResourceTable());
	}


	private void deleteResourceSyncTokenPerformed(AjaxRequestTarget target, ResourceType resourceType) {
		deleteSyncTokenPerformed(target, resourceType);
	}

	private void editResourcePerformed(ResourceType resourceType) {
		PageParameters parameters = new PageParameters();
		parameters.add(OnePageParameterEncoder.PARAMETER, resourceType.getOid());
		setResponsePage(new PageResourceWizard(parameters));
	}

	private void editAsXmlPerformed(ResourceType resourceType) {
		PageParameters parameters = new PageParameters();
		parameters.add(PageDebugView.PARAM_OBJECT_ID, resourceType.getOid());
		parameters.add(PageDebugView.PARAM_OBJECT_TYPE, ResourceType.class.getSimpleName());
		setResponsePage(PageDebugView.class, parameters);
	}

    private void clearSessionStorageForResourcePage() {
        ((PageBase) getPage()).getSessionStorage().clearResourceContentStorage();
    }

}
