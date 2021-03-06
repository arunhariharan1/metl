package org.jumpmind.metl.core.runtime.component;

import static org.apache.commons.lang.StringUtils.isBlank;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.jumpmind.metl.core.model.Model;
import org.jumpmind.metl.core.runtime.ControlMessage;
import org.jumpmind.metl.core.runtime.EntityData;
import org.jumpmind.metl.core.runtime.EntityDataMessage;
import org.jumpmind.metl.core.runtime.Message;
import org.jumpmind.metl.core.runtime.MisconfiguredException;
import org.jumpmind.metl.core.runtime.TextMessage;
import org.jumpmind.metl.core.runtime.flow.ISendMessageCallback;
import org.jumpmind.metl.core.runtime.resource.MailSession;
import org.jumpmind.util.FormatUtils;

public class EmailWriter extends AbstractComponentRuntime {

    final static String SUBJECT = "subject";
    final static String BODY = "body";
    final static String FROM_LINE = "from.line";
    final static String TO_LINE = "to.line";
    final static String CC_LINE = "cc.line";
    final static String BCC_LINE = "bcc.line";
    final static String SOURCE_STEP_EMAIL_ADDRESS = "source.step.email.addresses";
    final static String SOURCE_STEP_EMAIL_ADDRESS_TYPE = "source.step.email.addresses.type";
    final static String ONE_EMAIL_PER_RECIPIENT = "one.email.per.recipient";

    final static String VALUE_SOURCE_STEP_EMAIL_ADDRESS_TYPE_TO = "TO";
    final static String VALUE_SOURCE_STEP_EMAIL_ADDRESS_TYPE_CC = "CC";
    final static String VALUE_SOURCE_STEP_EMAIL_ADDRESS_TYPE_BCC = "BCC";

    boolean recipientsReady = false;

    List<Message> queuedMessages = new ArrayList<>();

    List<String> recipients = new ArrayList<>();

    MailSession mailSession;

    @Override
    public void start() {
        super.start();
        recipientsReady = false;
        mailSession = getResourceRuntime() != null ? getResourceRuntime().reference() : null;
        if (mailSession == null) {
            info("Using global mail session because a resource was not configured");
            mailSession = new MailSession(context.getGlobalSettings());
        }
    }

    @Override
    public void handle(Message inputMessage, ISendMessageCallback callback,
            boolean unitOfWorkBoundaryReached) {
        try {
            addToRecipients(inputMessage);
            queueMessageIfNecessary(inputMessage);
            processMessages(inputMessage, unitOfWorkBoundaryReached, callback);
        } catch (MessagingException e) {
            throw new RuntimeException(e);
        }
    }

    protected void queueMessageIfNecessary(Message inputMessage) {
        String sourceStepId = properties.get(SOURCE_STEP_EMAIL_ADDRESS);
        if (!recipientsReady
                && !inputMessage.getHeader().getOriginatingStepId().equals(sourceStepId)) {
            queuedMessages.add(inputMessage);
        }
    }

    protected void addToRecipients(Message inputMessage) {
        String sourceStepId = properties.get(SOURCE_STEP_EMAIL_ADDRESS);
        if (isBlank(sourceStepId)) {
            recipientsReady = true;
        } else if (inputMessage.getHeader().getOriginatingStepId().equals(sourceStepId)) {
            if (inputMessage instanceof ControlMessage) {
                recipientsReady = true;
            } else if (inputMessage instanceof TextMessage) {
                List<String> payload = ((TextMessage) inputMessage).getPayload();
                for (String emailAddress : payload) {
                    recipients.add(emailAddress);
                }
            } else if (inputMessage instanceof EntityDataMessage) {
                List<EntityData> payload = ((EntityDataMessage) inputMessage).getPayload();
                for (EntityData entityData : payload) {
                    if (entityData.size() > 0) {
                        Object firstValue = entityData.values().iterator().next();
                        if (firstValue != null) {
                            recipients.add(firstValue.toString());
                        }
                    }
                }
            }
        }
    }

    protected void processMessages(Message inputMessage, boolean unitOfWorkBoundaryReached, ISendMessageCallback callback)
            throws MessagingException {
        if (recipientsReady) {
            for (Message message : queuedMessages) {
                processMessage(message, unitOfWorkBoundaryReached, callback);
            }
            queuedMessages.clear();
            processMessage(inputMessage, unitOfWorkBoundaryReached, callback);
        }
    }

    protected void processMessage(Message inputMessage, boolean unitOfWorkBoundaryReached, ISendMessageCallback callback)
            throws MessagingException {
        String runWhen = properties.get(RUN_WHEN);
        if ((PER_UNIT_OF_WORK.equals(runWhen) && unitOfWorkBoundaryReached)
                || (PER_MESSAGE.equals(runWhen) && !(inputMessage instanceof ControlMessage)) || 
                PER_ENTITY.equals(runWhen)) {
            StringBuilder to = new StringBuilder(properties.get(TO_LINE, ""));
            StringBuilder from = new StringBuilder(properties.get(FROM_LINE, ""));
            StringBuilder cc = new StringBuilder(properties.get(CC_LINE, ""));
            StringBuilder bcc = new StringBuilder(properties.get(BCC_LINE, ""));
            String recipientType = properties.get(SOURCE_STEP_EMAIL_ADDRESS_TYPE);
            String subject = properties.get(SUBJECT, "");
            String body = properties.get(BODY, "");
            StringBuilder recipientBuilder = null;
            if (VALUE_SOURCE_STEP_EMAIL_ADDRESS_TYPE_TO.equals(recipientType)) {
                recipientBuilder = to;
            } else if (VALUE_SOURCE_STEP_EMAIL_ADDRESS_TYPE_CC.equals(recipientType)) {
                recipientBuilder = cc;
            } else if (VALUE_SOURCE_STEP_EMAIL_ADDRESS_TYPE_BCC.equals(recipientType)) {
                recipientBuilder = bcc;
            }
            boolean oneMessagePerRecipient = properties.is(ONE_EMAIL_PER_RECIPIENT);
            for (String recipient : recipients) {
                recipientBuilder.append(",").append(recipient);
                if (oneMessagePerRecipient) {
                    sendEmail(inputMessage, to.toString(), cc.toString(), bcc.toString(),
                            from.toString(), subject, body, callback);

                    recipientBuilder.replace(recipientBuilder.length() - recipient.length() - 1,
                            recipientBuilder.length(), "");
                }
            }

            if (!oneMessagePerRecipient) {
                sendEmail(inputMessage, to.toString(), cc.toString(), bcc.toString(),
                        from.toString(), subject, body, callback);
            }
        }
    }

    protected void sendEmail(Message inputMessage, String to, String cc, String bcc, String from,
            String subject, String body, ISendMessageCallback callback) throws MessagingException {
        String runWhen = properties.get(RUN_WHEN);
        to = resolveParamsAndHeaders(to, inputMessage);
        cc = resolveParamsAndHeaders(cc, inputMessage);
        bcc = resolveParamsAndHeaders(bcc, inputMessage);
        from = resolveParamsAndHeaders(from, inputMessage);
        subject = resolveParamsAndHeaders(subject, inputMessage);
        body = resolveParamsAndHeaders(body, inputMessage);
        if (PER_ENTITY.equals(runWhen)) {
            if (inputMessage instanceof TextMessage) {
                List<String> payload = ((TextMessage) inputMessage).getPayload();
                for (String text : payload) {
                    sendEmail(to.toString(), cc.toString(), bcc.toString(), from.toString(),
                            FormatUtils.replaceToken(subject, "text", text, true),
                            FormatUtils.replaceToken(body, "text", text, true), callback);
                }
            } else if (inputMessage instanceof EntityDataMessage) {
                List<EntityData> payload = ((EntityDataMessage) inputMessage).getPayload();
                for (EntityData entityData : payload) {
                    Model model = getInputModel();
                    Map<String, String> row = null;
                    if (model != null) {
                        row = model.toStringMap(entityData, true);
                    } else {
                        throw new MisconfiguredException(
                                "The input model is required if " + PER_ENTITY + " is selected");
                    }
                    sendEmail(to, cc, bcc, from, FormatUtils.replaceTokens(subject, row, true),
                            FormatUtils.replaceTokens(body, row, true), callback);
                }
            }
        } else {
            sendEmail(to, cc, bcc, from, subject, body, callback);
        }
    }

    protected void sendEmail(String to, String cc, String bcc, String from, String subject,
            String body, ISendMessageCallback callback) throws MessagingException {
        Transport transport = null;
        try {
            transport = mailSession.getTransport();
            MimeMessage mailMessage = new MimeMessage(mailSession.getSession());
            mailMessage.setSentDate(new Date());
            mailMessage.setRecipients(RecipientType.BCC, bcc);
            mailMessage.setRecipients(RecipientType.CC, cc);
            mailMessage.setRecipients(RecipientType.TO, to);
            mailMessage.setFrom(new InternetAddress(from));
            mailMessage.setSubject(subject);
            mailMessage.setText(body);
            transport.sendMessage(mailMessage, mailMessage.getAllRecipients());
            
            Map<String,Serializable> header = new LinkedHashMap<>();
            header.put("to", to);
            header.put("cc", cc);
            header.put("bcc", bcc);
            header.put("from", from);
            header.put("subject", subject);
            callback.sendTextMessage(header, body);

        } finally {
            mailSession.closeTransport();
        }
    }

    @Override
    public boolean supportsStartupMessages() {
        return true;
    }

}
