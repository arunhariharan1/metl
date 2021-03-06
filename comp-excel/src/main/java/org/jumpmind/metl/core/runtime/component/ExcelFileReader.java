package org.jumpmind.metl.core.runtime.component;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jumpmind.exception.IoException;
import org.jumpmind.metl.core.model.ComponentAttributeSetting;
import org.jumpmind.metl.core.model.Model;
import org.jumpmind.metl.core.runtime.ControlMessage;
import org.jumpmind.metl.core.runtime.EntityData;
import org.jumpmind.metl.core.runtime.Message;
import org.jumpmind.metl.core.runtime.MisconfiguredException;
import org.jumpmind.metl.core.runtime.flow.ISendMessageCallback;
import org.jumpmind.properties.TypedProperties;

public class ExcelFileReader extends AbstractFileReader {

    public static final int MAX_COLUMNS_PER_WORKSHEET = 1000;

    public static final String TYPE = "Excel File Reader";

    public static final String SETTING_ROWS_PER_MESSAGE = "rows.per.message";

    public static final String SETTING_HEADER_LINES_TO_SKIP = "header.lines.to.skip";

    public static final String SETTING_EXCEL_MAPPING = "excel.mapping";

    int rowsPerMessage = 1000;

    int headerLinesToSkip = 0;

    Model outputModel;

    Set<String> worsheetsToRead;

    Map<String, String[]> worksheetColumnListMap;

    @Override
    public void start() {
        init();
        outputModel = this.getOutputModel();
        TypedProperties properties = getTypedProperties();
        rowsPerMessage = properties.getInt(SETTING_ROWS_PER_MESSAGE, rowsPerMessage);
        headerLinesToSkip = properties.getInt(SETTING_HEADER_LINES_TO_SKIP, headerLinesToSkip);
        convertAttributeSettingsToMaps();
    }

    @Override
    public void handle(Message inputMessage, ISendMessageCallback callback,
            boolean unitOfWorkBoundaryReached) {
        if ((PER_UNIT_OF_WORK.equals(runWhen) && inputMessage instanceof ControlMessage)
                || (PER_MESSAGE.equals(runWhen) && !(inputMessage instanceof ControlMessage))) {
            List<String> files = getFilesToRead(inputMessage);
            processFiles(files, inputMessage, callback, unitOfWorkBoundaryReached);
        }
    }

    private void convertAttributeSettingsToMaps() {
        worksheetColumnListMap = new HashMap<String, String[]>();
        worsheetsToRead = new HashSet<String>();
        List<ComponentAttributeSetting> attributeSettings = getComponent().getAttributeSettings();
        for (ComponentAttributeSetting attributeSetting : attributeSettings) {
            String[] attributeValues = attributeSetting.getValue().split(":");
            if (attributeValues.length != 2) {
                throw new MisconfiguredException(
                        "Each attribute setting in the Excel Reader must be in the form <worksheet>:<column>");
            } else {
                String worksheetName = attributeValues[0];
                String columnReference = attributeValues[1].toUpperCase();
                String[] worksheetColumnArray = worksheetColumnListMap.get(worksheetName);
                if (worksheetColumnArray == null) {
                    worksheetColumnArray = new String[MAX_COLUMNS_PER_WORKSHEET];
                    worksheetColumnListMap.put(worksheetName, worksheetColumnArray);
                }
                worksheetColumnArray[calculateColumnIndex(columnReference)] = attributeSetting
                        .getAttributeId();
                worsheetsToRead.add(attributeValues[0]);
            }
        }
    }

    private int calculateColumnIndex(String columnReference) {
        int columnIdx = 0;
        for (int i = 0; i < columnReference.length(); i++) {
            int decimalValue = (int) columnReference.charAt(i);
            columnIdx = columnIdx + (decimalValue - 64) + i * 26 - 1;
        }
        return columnIdx;
    }

    private void processFiles(List<String> files, Message inputMessage,
            ISendMessageCallback callback, boolean unitOfWorkLastMessage) {

        filesRead.addAll(files);

        for (String file : files) {
            Map<String, Serializable> headers = new HashMap<>(1);
            headers.put("source.file.path", file);

            InputStream inStream = null;
            try {
                info("Reading file: %s", file);
                String filePath = resolveParamsAndHeaders(file, inputMessage);
                inStream = directory.getInputStream(filePath, mustExist);
                if (inStream != null) {
                    readWorkbook(headers, inStream, callback);
                }
            } catch (IOException e) {
                throw new IoException("Error reading from file " + e.getMessage());
            } finally {
                IOUtils.closeQuietly(inStream);
            }
            if (controlMessageOnEof) {
                callback.sendControlMessage(headers);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void readWorkbook(Map<String, Serializable> headers, InputStream inStream,
            ISendMessageCallback callback) throws IOException {

        int linesInMessage = 0;
        ArrayList<EntityData> outboundPayload = new ArrayList<EntityData>();
        int currentFileLinesRead = 1;

        Workbook wb = new XSSFWorkbook(inStream);
        try {
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet sheet = wb.getSheetAt(i);
            if (worsheetsToRead.contains(sheet.getSheetName())) {
                String[] worksheetColumnArray = worksheetColumnListMap.get(sheet.getSheetName());
                for (Row row : sheet) {
                    if (currentFileLinesRead > headerLinesToSkip) {
                        EntityData data = new EntityData();
                        Object cellValue = null;
                        for (Cell cell : row) {
                            if (worksheetColumnArray[cell.getColumnIndex()] != null) {
                                switch (cell.getCellType()) {
                                    case Cell.CELL_TYPE_STRING:
                                        cellValue = cell.getStringCellValue();
                                        break;
                                    case Cell.CELL_TYPE_BOOLEAN:
                                        cellValue = cell.getBooleanCellValue();
                                        break;
                                    case Cell.CELL_TYPE_NUMERIC:
                                        if (DateUtil.isCellDateFormatted(cell)) {
                                            cellValue = cell.getDateCellValue();
                                        } else {
                                            cellValue = cell.getNumericCellValue();
                                        }
                                        break;
                                    case Cell.CELL_TYPE_BLANK:
                                        cellValue = null;
                                        break;
                                    default:
                                        throw new UnsupportedOperationException(
                                                "Invalid cell type value.  Cell Type ==>"
                                                        + cell.getCellType());
                                }
                                data.put(worksheetColumnArray[cell.getColumnIndex()], cellValue);
                            } //end if worksheetColumnArray != null i.e. this cell is mapped
                        } // end for cells
                        getComponentStatistics().incrementNumberEntitiesProcessed(threadNumber);
                        outboundPayload.add(data);
                        linesInMessage++;
                        if (linesInMessage == rowsPerMessage) {
                            callback.sendEntityDataMessage(headers, outboundPayload);
                            linesInMessage = 0;
                            outboundPayload = new ArrayList<EntityData>();
                        }
                    } // if we are done skipping lines
                    currentFileLinesRead++;
                } // end for rows
            } // if we should read this worksheet
        } // for each worksheet
          // send leftovers
        if (outboundPayload.size() > 0) {
            callback.sendEntityDataMessage(headers, outboundPayload);
        }
        } finally {
            IOUtils.closeQuietly(wb);
        }
    }
}
