package org.jumpmind.symmetric.is.ui.views.manage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.jumpmind.symmetric.is.core.model.Execution;
import org.jumpmind.symmetric.is.core.model.ExecutionStep;
import org.jumpmind.symmetric.is.core.model.ExecutionStepLog;
import org.jumpmind.symmetric.is.core.model.FlowVersion;
import org.jumpmind.symmetric.is.core.persist.IExecutionService;
import org.jumpmind.symmetric.is.ui.common.IBackgroundRefreshable;
import org.jumpmind.symmetric.is.ui.init.BackgroundRefresherService;
import org.jumpmind.symmetric.ui.common.IUiPanel;

import com.vaadin.data.Property.ValueChangeEvent;
import com.vaadin.data.Property.ValueChangeListener;
import com.vaadin.data.util.BeanContainer;
import com.vaadin.data.util.BeanItem;
import com.vaadin.data.util.BeanItemContainer;
import com.vaadin.shared.ui.MarginInfo;
import com.vaadin.shared.ui.label.ContentMode;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Table;
import com.vaadin.ui.Table.ColumnGenerator;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.VerticalSplitPanel;

public class ExecutionLogPanel extends VerticalLayout implements IUiPanel, IBackgroundRefreshable {

	private static final long serialVersionUID = 1L;
	
	protected IExecutionService executionService;
	
	protected FlowVersion flowVersion;
	
	protected Table stepTable = new Table();
	
	protected BeanContainer<String, ExecutionStep> stepContainer = new BeanContainer<String, ExecutionStep>(ExecutionStep.class);
	
	protected BeanItemContainer<ExecutionStepLog> logContainer = new BeanItemContainer<ExecutionStepLog>(ExecutionStepLog.class);
	
	protected Label flowLabel = new Label();
	
	protected Label statusLabel = new Label();
	
	protected Label startLabel = new Label();
	
	protected Label endLabel = new Label();
	
	protected BackgroundRefresherService backgroundRefresherService;
	
	protected String executionId;

	public ExecutionLogPanel(String executionId, final BackgroundRefresherService backgroundRefresherService, final IExecutionService executionService,
			final FlowVersion flowVersion) {
		this.backgroundRefresherService = backgroundRefresherService;
		this.executionService = executionService;
		this.flowVersion = flowVersion;
		this.executionId = executionId;

		HorizontalLayout header1 = new HorizontalLayout();
		header1.addComponent(new Label("<b>Flow:</b>", ContentMode.HTML));
		header1.addComponent(flowLabel);
		header1.addComponent(new Label("<b>Start:</b>", ContentMode.HTML));
		header1.addComponent(startLabel);
		header1.setSpacing(true);
		header1.setMargin(new MarginInfo(true, true, false, true));
		header1.setWidth("100%");
		addComponent(header1);
		
		HorizontalLayout header2 = new HorizontalLayout();
		header2.addComponent(new Label("<b>Status:</b>", ContentMode.HTML));
		header2.addComponent(statusLabel);
		header2.addComponent(new Label("<b>End:</b>", ContentMode.HTML));
		header2.addComponent(endLabel);
		header2.setSpacing(true);
		header2.setMargin(new MarginInfo(false, true, true, true));
		header2.setWidth("100%");
		addComponent(header2);
		
		stepContainer.setBeanIdProperty("id");
		stepTable.setContainerDataSource(stepContainer);
		stepTable.setSelectable(true);
		stepTable.setMultiSelect(true);
		stepTable.setImmediate(true);
		stepTable.setSizeFull();
		stepTable.setVisibleColumns(new Object[] { "componentName", "status", "messagesReceived", "messagesProduced", "startTime", "endTime" });
		stepTable.setColumnHeaders(new String[] { "Component Name", "Status", "Msgs Recvd", "Msgs Sent", "Start", "End" });
		stepTable.addValueChangeListener(new ValueChangeListener() {
			private static final long serialVersionUID = 1L;

			@Override
			public void valueChange(ValueChangeEvent event) {
				@SuppressWarnings("unchecked")
				Set<String> executionStepIds = (Set<String>) event.getProperty().getValue();
				logContainer.removeAllItems();
				List<ExecutionStepLog> logs = executionService.findExecutionStepLog(executionStepIds);
				logContainer.addAll(logs);
			}			
		});

		Table logTable = new Table();
		logTable.setContainerDataSource(logContainer);
		logTable.setSelectable(true);
		logTable.setMultiSelect(true);
		logTable.setSizeFull();
		logTable.addGeneratedColumn("componentName", new ComponentNameColumnGenerator());
		logTable.setVisibleColumns(new Object[] { "componentName", "level", "createTime", "logText" });
		logTable.setColumnHeaders(new String[] { "Component Name", "Level", "Time", "Description" });

		VerticalSplitPanel splitPanel = new VerticalSplitPanel();
		splitPanel.setFirstComponent(stepTable);
		splitPanel.setSecondComponent(logTable);
		splitPanel.setSplitPosition(50f);
		splitPanel.setSizeFull();
		addComponent(splitPanel);
		setExpandRatio(splitPanel, 1.0f);
		
		refreshUI(getExecutionData());
		backgroundRefresherService.register(this);
	}
	
    @Override
    public boolean closing() {
        backgroundRefresherService.unregister(this);
        return true;
    }

    @Override
    public void showing() {
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object onBackgroundDataRefresh() {
        return getExecutionData();
    }

    @Override
    public void onBackgroundUIRefresh(Object backgroundData) {
    	refreshUI((ExecutionData) backgroundData);
    }

    @SuppressWarnings("unchecked")
	protected ExecutionData getExecutionData() {
    	ExecutionData data = new ExecutionData();
    	data.execution = executionService.findExecution(executionId);
    	data.steps = executionService.findExecutionStep(executionId);
    	data.logs = executionService.findExecutionStepLog((Set<String>) stepTable.getValue());
		return data;
    }
    
    protected void refreshUI(ExecutionData data) {
		flowLabel.setValue(flowVersion.getName());
		startLabel.setValue(formatDate(data.execution.getStartTime()));
		statusLabel.setValue(data.execution.getStatus());
		endLabel.setValue(formatDate(data.execution.getEndTime()));
    	stepContainer.removeAllItems();
    	stepContainer.addAll(data.steps);
    	logContainer.removeAllItems();
		logContainer.addAll(data.logs);
    }

	protected String formatDate(Date date) {
		SimpleDateFormat df = new SimpleDateFormat("MMM dd, yyyy hh:mm:ss aa");
		if (date != null) {
			return df.format(date);
		}
		return "";
	}

	public class ExecutionData {
    	public Execution execution;
		public List<ExecutionStep> steps;
		public List<ExecutionStepLog> logs;		
	}

	public class ComponentNameColumnGenerator implements ColumnGenerator {
        private static final long serialVersionUID = 1L;

        @SuppressWarnings("unchecked")
        public Object generateCell(Table source, Object itemId, Object columnId) {
            BeanItem<ExecutionStepLog> logItem = (BeanItem<ExecutionStepLog>) source.getItem(itemId);
            String executionStepId = (String) logItem.getItemProperty("executionStepId").getValue();
            BeanItem<ExecutionStep> stepItem = stepContainer.getItem(executionStepId);
            return new Label((String) stepItem.getItemProperty("componentName").getValue());
        }
    }

}
