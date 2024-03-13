/**
 * Yona, 21st Century Project Hosting SW
 * <p>
 * Copyright Yona & Yobi Authors & NAVER Corp. & NAVER LABS Corp.
 * https://yona.io
 **/

package models;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.jgit.revwalk.RevCommit;
import org.apache.commons.lang3.StringUtils;

import models.enumeration.State;
import models.enumeration.EventType;
import models.enumeration.PullRequestReviewAction;
import models.enumeration.ResourceType;
import models.enumeration.WebhookType;
import models.resource.GlobalResource;
import models.resource.Resource;
import models.resource.ResourceConvertible;

import utils.RouteUtil;

import play.Logger;
import play.api.i18n.Lang;
import play.data.validation.Constraints.Required;
import play.db.ebean.Model;
import play.i18n.Messages;
import play.libs.F.Function;
import play.libs.Json;
import play.libs.ws.WS;
import play.libs.ws.WSRequestHolder;
import play.libs.ws.WSResponse;
import play.Play;

import playRepository.GitCommit;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.ManyToOne;
import javax.validation.constraints.Size;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * A webhook to be sent by events in project
 */
@Entity
public class Webhook extends Model implements ResourceConvertible {

    private static final long serialVersionUID = 1L;
    public static final Finder<Long, Webhook> find = new Finder<>(Long.class, Webhook.class);

    /**
     * Primary Key.
     */
    @Id
    public Long id;

    /**
     * Project which have this webhook.
     */
    @ManyToOne
    public Project project;

    /**
     * Payload URL of webhook.
     */
    @Required
    @Size(max=2000, message="project.webhook.payloadUrl.tooLong")
    public String payloadUrl;

    /**
     * Secret token for server identity.
     */
    @Size(max=250, message="project.webhook.secret.tooLong")
    public String secret;

    /**
     * Condition of sending webhook (true = include git push event, false = exclude git push event)
     */
    public Boolean gitPush;

    public WebhookType webhookType = WebhookType.SIMPLE;

    public Date createdAt;


    /**
     * Construct a webhook by the given {@code payloadUrl} and {@code secret}.
     *
     * @param projectId the ID of project which will have this webhook
     * @param payloadUrl the payload URL for this webhook
     * @param gitPush type of webhook (true = include git push event, false = exclude git push event)
     * @param secret the secret token for server identity
     */
    public Webhook(Long projectId, String payloadUrl, String secret, Boolean gitPush, WebhookType webhookType) {
        if (secret == null) {
            secret = "";
        }
        this.project = Project.find.byId(projectId);
        this.payloadUrl = payloadUrl;
        this.secret = secret;
        this.gitPush = gitPush;
        this.webhookType = webhookType;
        this.createdAt = new Date();
    }

    /**
     * Returns a {@link Resource} representation of this webhook.
     *
     * {@link utils.AccessControl}.may use this method to check if an user has
     * a permission to access this label.
     *
     * @return a {@link Resource} representation of this webhook
     */
    @Override
    public Resource asResource() {
        return new GlobalResource() {
            @Override
            public String getId() {
                return id.toString();
            }

            @Override
            public ResourceType getType() {
                return ResourceType.WEBHOOK;
            }
        };
    }
    
    public static List<Webhook> findByProject(Long projectId) {
        return find.where().eq("project.id", projectId).findList();
    }

    public static void create(Long projectId, String payloadUrl, String secret, Boolean gitPush, WebhookType webhookType) {
        if (!payloadUrl.isEmpty()) {
            Webhook webhook = new Webhook(projectId, payloadUrl, secret, gitPush, webhookType);
            webhook.save();
        }
        // TODO : Raise appropriate error when required field is empty
    }

    public static void delete(Long webhookId, Long projectId) {
        Webhook.findByIds(webhookId, projectId).delete();
    }

    /**
     * Remove this webhook from a project.
     *
     * @param projectId ID of the project from which this webhook is removed
     */
    public void delete(Long projectId) {
        Project targetProject = Project.find.byId(projectId);
        targetProject.webhooks.remove(this);
        targetProject.update();
        super.delete();
    }

    public static Webhook findByIds(Long webhookId, Long projectId) {
        return find.where()
                .eq("webhook.id", webhookId)
                .eq("project.id", projectId)
                .findUnique();
    }

    public static Webhook findById(Long webhookId) {
        return find.where()
                .eq("id", webhookId)
                .findUnique();
    }

    private String getBaseUrl() {
        return String.format("%s://%s", utils.Config.getScheme(), utils.Config.getHostport("localhost:9000"));
    }

	private String buildRequestMessage(String url, String message) {
        StringBuilder requestMessage = new StringBuilder();

        // TEAMS 전용 메세지일 경우 해당 정보로 전송
        if (this.webhookType == WebhookType.TEAMS) {
            requestMessage.append(String.format(" [%s](%s%s)", message, getBaseUrl(), url));
        } else {
            requestMessage.append(String.format(" <%s%s|", getBaseUrl(), url));
            if (this.webhookType == WebhookType.DETAIL_SLACK) {
                requestMessage.append(message.replace(">", "&gt;"));
            } else {
                requestMessage.append(message);
            }
            requestMessage.append(">");
        }
        
        return requestMessage.toString();
    }

    // Issue
    public void sendRequestToPayloadUrl(EventType eventType, User sender, Issue eventIssue) {
        String requestBodyString = "";
        String requestMessage = buildRequestBody(eventType, sender, eventIssue);

        if (this.webhookType == WebhookType.DETAIL_SLACK) {
            ArrayNode attachments = buildIssueDetails(eventIssue, eventType);
            requestBodyString = buildRequestJsonWithAttachments(requestMessage, attachments);
        } else if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            ObjectNode thread = buildThreadJSON(eventIssue.asResource());
            requestBodyString = buildRequestJsonWithThread(requestMessage, thread);
        } else if (this.webhookType == WebhookType.TEAMS) {
			requestBodyString = buildRequestBodyTEAMS(eventType, sender, eventIssue);
		}
		else {
            requestBodyString = buildTextPropertyOnlyJSON(requestMessage);
        }

        if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            sendRequest(requestBodyString, this.id, eventIssue.asResource());
        } else {
            sendRequest(requestBodyString);
        }
    }
	
	// ISSUE (TEAMS)
	private String buildRequestBodyTEAMS(EventType eventType, User sender, Issue eventIssue) {	
		// issueType
		String strType = "";
		switch (eventType) {
			case NEW_ISSUE:
				strType = "새 이슈 등록";
				break;
			case ISSUE_STATE_CHANGED:
				if(eventIssue.state == State.CLOSED)
					strType = "이슈 종료";
				else if(eventIssue.state == State.OPEN)
					strType = "이슈 재시작";
				break;
			case ISSUE_ASSIGNEE_CHANGED:
				strType = "이슈 담당자 변경";
				break;
			case ISSUE_BODY_CHANGED:
				strType ="이슈 본문 변경";
				break;
			case ISSUE_MILESTONE_CHANGED:
				strType = "마일 스톤 변경";
				break;
			case RESOURCE_DELETED:
				strType = "이슈 삭제";
				break;
			default:
				play.Logger.warn(String.format("Unknown webhook event: %s", eventType));
		}
	
		// 노드 선언
		ObjectNode templeteJson = Json.newObject();
		ObjectNode attachmentJson = Json.newObject();
		ObjectNode contentJson = Json.newObject();
		
		ObjectMapper attachmentInit = new ObjectMapper();
        ArrayNode attachmentsArray = attachmentInit.createArrayNode();
		
		ObjectMapper bodyInit = new ObjectMapper();
        ArrayNode bodyArray = bodyInit.createArrayNode();
		
		ObjectMapper containerInit = new ObjectMapper();
        ArrayNode containerArray = containerInit.createArrayNode();
		
		// 요소 입력
		templeteJson.put("type","message");
		templeteJson.put("attachments", attachmentsArray);
		attachmentsArray.add(attachmentJson);
		
		attachmentJson.put("contentType", "application/vnd.microsoft.card.adaptive");
		attachmentJson.put("contentUrl", "null");
		attachmentJson.put("content", contentJson);
		
		contentJson.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
		contentJson.put("type", "AdaptiveCard");
		contentJson.put("version", "1.2");
		contentJson.put("body", bodyArray);
		
		bodyArray.add(BuildTitleTEAMS(strType));
		bodyArray.add(BuildSubTitleTEAMS(eventType, sender, eventIssue));
		
		return Json.stringify(templeteJson);
	}

    private String buildRequestBody(EventType eventType, User sender, Issue eventIssue) {
        StringBuilder requestMessage = new StringBuilder();
        requestMessage.append(String.format("[%s] %s ", project.name, sender.name));

			switch (eventType) {
				case NEW_ISSUE:
					requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.new.issue"));
					break;
				case ISSUE_STATE_CHANGED:
					requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.issue.state.changed"));
					break;
				case ISSUE_ASSIGNEE_CHANGED:
					requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.issue.assignee.changed"));
					break;
				case ISSUE_BODY_CHANGED:
					requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.issue.body.changed"));
					break;
				case ISSUE_MILESTONE_CHANGED:
					requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.milestone.changed"));
					break;
				case RESOURCE_DELETED:
					requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.issue.deleted"));
					break;
				default:
					play.Logger.warn(String.format("Unknown webhook event: %s", eventType));
			}

        String eventIssueUrl = controllers.routes.IssueApp.issue(eventIssue.project.owner, eventIssue.project.name, eventIssue.getNumber()).url();
        requestMessage.append(buildRequestMessage(eventIssueUrl, String.format("#%d: %s", eventIssue.number, eventIssue.title)));
        return requestMessage.toString();
    }

    // Issue transfer
    public void sendRequestToPayloadUrl(EventType eventType, User sender, Issue eventIssue, Project previous) {
        String requestBodyString = "";
        String requestMessage = buildRequestBody(eventType, sender, eventIssue, previous);

        if (this.webhookType == WebhookType.DETAIL_SLACK) {
            ArrayNode attachments = buildIssueDetails(eventIssue, eventType);
            requestBodyString = buildRequestJsonWithAttachments(requestMessage, attachments);
        } else if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            ObjectNode thread = buildThreadJSON(eventIssue.asResource());
            requestBodyString = buildRequestJsonWithThread(requestMessage, thread);
        } else if (this.webhookType == WebhookType.TEAMS) {
			requestBodyString = buildRequestBodyTEAMS(eventType, sender, eventIssue);
		}
		else {
            requestBodyString = buildTextPropertyOnlyJSON(requestMessage);
        }

        if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            sendRequest(requestBodyString, this.id, eventIssue.asResource());
        } else {
            sendRequest(requestBodyString);
        }
    }

	// Issue transfer (TEAMS) 구현필요
	private String buildRequestBodyTEAMS(EventType eventType, User sender, Issue eventIssue, Project previous) {
		
		return "";
	}

    private String buildRequestBody(EventType eventType, User sender, Issue eventIssue, Project previous) {
        StringBuilder requestMessage = new StringBuilder();
        requestMessage.append(String.format("[%s] %s ", project.name, sender.name));
        requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.issue.moved", previous.name, project.name));
        requestMessage.append(
                buildRequestMessage(
                        controllers.routes.IssueApp.issue(eventIssue.project.owner, eventIssue.project.name, eventIssue.getNumber()).url(),
                        String.format("#%d: %s", eventIssue.number, eventIssue.title)
                    )
        );
        return requestMessage.toString();
    }

    // Issue Detail (Slack)
    private ArrayNode buildIssueDetails(Issue eventIssue, EventType eventType) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode attachments = mapper.createArrayNode();
        ArrayNode detailFields = mapper.createArrayNode();

        if (eventIssue.milestone != null) {
            detailFields.add(buildTitleValueJSON(Messages.get(Lang.defaultLang(), "notification.type.milestone.changed"), eventIssue.milestone.title, true));
        }
        detailFields.add(buildTitleValueJSON(Messages.get(Lang.defaultLang(), ""), eventIssue.assigneeName(), true));
        detailFields.add(buildTitleValueJSON(Messages.get(Lang.defaultLang(), "issue.state"), eventIssue.state.toString(), true));

        attachments.add(buildAttachmentJSON(eventIssue.body, detailFields, eventType));

        return attachments;
    }

    // Posting
    public void sendRequestToPayloadUrl(EventType eventType, User sender, Posting eventPost) {
        String requestBodyString = "";
        String requestMessage = buildRequestBody(eventType, sender, eventPost);
				

        if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            ObjectNode thread = buildThreadJSON(eventPost.asResource());
            requestBodyString = buildRequestJsonWithThread(requestMessage, thread);
        } else if (this.webhookType == WebhookType.TEAMS) {
			requestBodyString = buildRequestBodyTEAMS(eventType, sender, eventPost);
		}
		else {
            requestBodyString = buildTextPropertyOnlyJSON(requestMessage);
        }

        if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            sendRequest(requestBodyString, this.id, eventPost.asResource());
        } else {
            sendRequest(requestBodyString);
        }
    }

	// Posting (TEAMS)
	private String buildRequestBodyTEAMS(EventType eventType, User sender, Posting eventPost) {
		// PostingType
		String strType = "";
		switch (eventType) {
            case NEW_POSTING:
                strType = "새 게시물 등록";
                break;
            default:
                play.Logger.warn("Unknown webhook event: " + eventType);
        }
		
			// 노드 선언
		ObjectNode templeteJson = Json.newObject();
		ObjectNode attachmentJson = Json.newObject();
		ObjectNode contentJson = Json.newObject();
		
		ObjectMapper attachmentInit = new ObjectMapper();
        ArrayNode attachmentsArray = attachmentInit.createArrayNode();
		
		ObjectMapper bodyInit = new ObjectMapper();
        ArrayNode bodyArray = bodyInit.createArrayNode();
		
		ObjectMapper containerInit = new ObjectMapper();
        ArrayNode containerArray = containerInit.createArrayNode();
		
		// 요소 입력
		templeteJson.put("type","message");
		templeteJson.put("attachments", attachmentsArray);
		attachmentsArray.add(attachmentJson);
		
		attachmentJson.put("contentType", "application/vnd.microsoft.card.adaptive");
		attachmentJson.put("contentUrl", "null");
		attachmentJson.put("content", contentJson);
		
		contentJson.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
		contentJson.put("type", "AdaptiveCard");
		contentJson.put("version", "1.2");
		contentJson.put("body", bodyArray);
		
		bodyArray.add(BuildTitleTEAMS(strType));
		bodyArray.add(BuildSubTitleTEAMS(sender, eventPost));
		
		return Json.stringify(templeteJson);
	}

    private String buildRequestBody(EventType eventType, User sender, Posting eventPost) {
        StringBuilder requestMessage = new StringBuilder();
        requestMessage.append(String.format("[%s] %s ", project.name, sender.name));

        switch (eventType) {
            case NEW_POSTING:
                requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.new.posting"));
                break;
            default:
                play.Logger.warn("Unknown webhook event: " + eventType);
        }

        String eventPostUrl = RouteUtil.getUrl(eventPost);
        requestMessage.append(buildRequestMessage(eventPostUrl, String.format("#%d: %s", eventPost.number, eventPost.title)));
        return requestMessage.toString();
    }

    // Comment
    public void sendRequestToPayloadUrl(EventType eventType, User sender, Comment eventComment) {
        String requestBodyString = "";
        String requestMessage = buildRequestBody(eventType, sender, eventComment);

        if (this.webhookType == WebhookType.DETAIL_SLACK) {
            ArrayNode attachments = buildCommentDetails(eventComment, eventType);
            requestBodyString = buildRequestJsonWithAttachments(requestMessage, attachments);
        } else if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            ObjectNode thread = buildThreadJSON(eventComment.getParent().asResource());
            requestBodyString = buildRequestJsonWithThread(requestMessage, thread);
        } else if (this.webhookType == WebhookType.TEAMS) {
			requestBodyString = buildRequestBodyTEAMS(eventType, sender, eventComment);
		} else {
            requestBodyString = buildTextPropertyOnlyJSON(requestMessage);
        }

        if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            sendRequest(requestBodyString, this.id, eventComment.getParent().asResource());
        } else {
            sendRequest(requestBodyString);
        }
    }
	
	// Comment (TEAMS)
	private String buildRequestBodyTEAMS(EventType eventType, User sender, Comment eventComment) {
		
		// commentType
		String strType = "";
		switch (eventType) {
            case NEW_COMMENT:
                strType = "새 댓글 등록";
                break;
            case COMMENT_UPDATED:
                strType = "댓글 수정";
                break;
        }
		
		// 노드 선언
		ObjectNode templeteJson = Json.newObject();
		ObjectNode attachmentJson = Json.newObject();
		ObjectNode contentJson = Json.newObject();
		
		ObjectMapper attachmentInit = new ObjectMapper();
        ArrayNode attachmentsArray = attachmentInit.createArrayNode();
		
		ObjectMapper bodyInit = new ObjectMapper();
        ArrayNode bodyArray = bodyInit.createArrayNode();
		
		ObjectMapper containerInit = new ObjectMapper();
        ArrayNode containerArray = containerInit.createArrayNode();
		
		// 요소 입력
		templeteJson.put("type","message");
		templeteJson.put("attachments", attachmentsArray);
		attachmentsArray.add(attachmentJson);
		
		attachmentJson.put("contentType", "application/vnd.microsoft.card.adaptive");
		attachmentJson.put("contentUrl", "null");
		attachmentJson.put("content", contentJson);
		
		contentJson.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
		contentJson.put("type", "AdaptiveCard");
		contentJson.put("version", "1.2");
		contentJson.put("body", bodyArray);
		
		bodyArray.add(BuildTitleTEAMS(strType));
		bodyArray.add(BuildSubTitleTEAMS(sender, eventComment));
		
		return Json.stringify(templeteJson);
	}
	
    private String buildRequestBody(EventType eventType, User sender, Comment eventComment) {
        StringBuilder requestMessage = new StringBuilder();
        requestMessage.append(String.format("[%s] %s ", project.name, sender.name));

        switch (eventType) {
            case NEW_COMMENT:
                requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.new.comment"));
                break;
            case COMMENT_UPDATED:
                requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.comment.updated"));
                break;
        }

        requestMessage.append(buildRequestMessage(RouteUtil.getUrl(eventComment), String.format("#%d: %s", eventComment.getParent().number, eventComment.getParent().title)));
        return requestMessage.toString();
    }

    // Comment Detail (Slack)
    private ArrayNode buildCommentDetails(Comment eventComment, EventType eventType) {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode attachments = mapper.createArrayNode();

        attachments.add(buildAttachmentJSON(eventComment.contents, null, eventType));

        return attachments;
    }

    // Pull Request
    public void sendRequestToPayloadUrl(EventType eventType, User sender, PullRequest eventPullRequest) {
        String requestBodyString = "";
        String requestMessage = buildRequestBody(eventType, sender, eventPullRequest);

        if (this.webhookType == WebhookType.DETAIL_SLACK) {
            ArrayNode attachments = buildJsonWithPullReqtuestDetails(eventPullRequest, requestMessage, eventType);
            requestBodyString = buildRequestJsonWithAttachments(requestMessage, attachments);
        } else if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            ObjectNode thread = buildThreadJSON(eventPullRequest.asResource());
            requestBodyString = buildRequestJsonWithThread(requestMessage, thread);
        } else {
            requestBodyString = buildTextPropertyOnlyJSON(requestMessage);
        }

        if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            sendRequest(requestBodyString, this.id, eventPullRequest.asResource());
        } else {
            sendRequest(requestBodyString);
        }
    }

    private String buildRequestBody(EventType eventType, User sender, PullRequest eventPullRequest) {
        StringBuilder requestMessage = new StringBuilder();
        requestMessage.append(String.format("[%s] %s ", project.name, sender.name));

        switch (eventType) {
            case NEW_PULL_REQUEST:
                requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.new.pullrequest"));
                break;
            case PULL_REQUEST_STATE_CHANGED:
                requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.pullrequest.state.changed"));
                break;
            case PULL_REQUEST_MERGED:
                requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.pullrequest.merged"));
                break;
            case PULL_REQUEST_COMMIT_CHANGED:
                requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.pullrequest.commit.changed"));
                break;
        }

        requestMessage.append(buildRequestMessage(RouteUtil.getUrl(eventPullRequest), String.format("#%d: %s", eventPullRequest.number, eventPullRequest.title)));
        return requestMessage.toString();
    }

    // Pull Request Review
    public void sendRequestToPayloadUrl(EventType eventType, User sender, PullRequest eventPullRequest, PullRequestReviewAction reviewAction) {
        String requestBodyString = "";
        String requestMessage = buildRequestBody(eventType, sender, eventPullRequest, reviewAction);

        if (this.webhookType == WebhookType.DETAIL_SLACK) {
            ArrayNode attachments = buildJsonWithPullReqtuestDetails(eventPullRequest, requestMessage, eventType);
            requestBodyString = buildRequestJsonWithAttachments(requestMessage, attachments);
        } else if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            ObjectNode thread = buildThreadJSON(eventPullRequest.asResource());
            requestBodyString = buildRequestJsonWithThread(requestMessage, thread);
        } else {
            requestBodyString = buildTextPropertyOnlyJSON(requestMessage);
        }

        if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            sendRequest(requestBodyString, this.id, eventPullRequest.asResource());
        } else {
            sendRequest(requestBodyString);
        }
    }

    private String buildRequestBody(EventType eventType, User sender, PullRequest eventPullRequest, PullRequestReviewAction reviewAction) {
        StringBuilder requestMessage = new StringBuilder();
        requestMessage.append(String.format("[%s] ", project.name));

        switch (eventType) {
            case PULL_REQUEST_REVIEW_STATE_CHANGED:
                if (PullRequestReviewAction.DONE.equals(reviewAction)) {
                    requestMessage.append(Messages.get(Lang.defaultLang(), "notification.pullrequest.reviewed", sender.name));
                } else {
                    requestMessage.append(Messages.get(Lang.defaultLang(), "notification.pullrequest.unreviewed", sender.name));
                }
                break;
        }

        requestMessage.append(buildRequestMessage(RouteUtil.getUrl(eventPullRequest), String.format("#%d: %s", eventPullRequest.number, eventPullRequest.title)));
        return requestMessage.toString();
    }

    // Pull Request Comment
    public void sendRequestToPayloadUrl(EventType eventType, User sender, PullRequest eventPullRequest, ReviewComment reviewComment) {
        String requestBodyString = "";
        String requestMessage = buildRequestBody(eventType, sender, eventPullRequest, reviewComment);

        if (this.webhookType == WebhookType.DETAIL_SLACK) {
            ArrayNode attachments = buildJsonWithPullReqtuestDetails(eventPullRequest, requestMessage, eventType);
            requestBodyString = buildRequestJsonWithAttachments(requestMessage, attachments);
        } else if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            ObjectNode thread = buildThreadJSON(eventPullRequest.asResource());
            requestBodyString = buildRequestJsonWithThread(requestMessage, thread);
        } else {
            requestBodyString = buildTextPropertyOnlyJSON(requestMessage);
        }

        if (this.webhookType == WebhookType.DETAIL_HANGOUT_CHAT) {
            sendRequest(requestBodyString, this.id, eventPullRequest.asResource());
        } else {
            sendRequest(requestBodyString);
        }
    }

    private String buildRequestBody(EventType eventType, User sender, PullRequest eventPullRequest, ReviewComment reviewComment) {
        StringBuilder requestMessage = new StringBuilder();
        requestMessage.append(String.format("[%s] %s ", project.name, sender.name));
        requestMessage.append(Messages.get(Lang.defaultLang(), "notification.type.new.simple.comment"));
        requestMessage.append(String.format(" <%s://%s%s|", utils.Config.getScheme(), utils.Config.getHostport("localhost:9000"), RouteUtil.getUrl(reviewComment)));
        requestMessage.append(String.format("#%d: %s>", eventPullRequest.number, eventPullRequest.title));
        return requestMessage.toString();
    }

    // Pull Request Detail (Slack)
    private ArrayNode buildJsonWithPullReqtuestDetails(PullRequest eventPullRequest, String requestMessage, EventType eventType) {
        ObjectMapper mapper = new ObjectMapper();

        ArrayNode detailFields = mapper.createArrayNode();
        detailFields.add(buildTitleValueJSON(Messages.get(Lang.defaultLang(), "pullRequest.sender"), eventPullRequest.contributor.name, false));
        detailFields.add(buildTitleValueJSON(Messages.get(Lang.defaultLang(), "pullRequest.from"), eventPullRequest.fromBranch, true));
        detailFields.add(buildTitleValueJSON(Messages.get(Lang.defaultLang(), "pullRequest.to"), eventPullRequest.toBranch, true));

        ArrayNode attachments = mapper.createArrayNode();
        attachments.add(buildAttachmentJSON(eventPullRequest.body, detailFields, eventType));

        return attachments;
    }

    private String buildTextPropertyOnlyJSON(String requestMessage) {
        ObjectNode requestBody = Json.newObject();
        requestBody.put("text", requestMessage);
        return Json.stringify(requestBody);
    }

    private String buildRequestJsonWithAttachments(String requestMessage, ArrayNode attachments) {
        ObjectNode requestBody = Json.newObject();
        requestBody.put("text", requestMessage);
        requestBody.put("attachments", attachments);
        return Json.stringify(requestBody);
    }

    private String buildRequestJsonWithThread(String requestMessage, ObjectNode thread) {
        ObjectNode requestBody = Json.newObject();
        requestBody.put("text", requestMessage);
        requestBody.put("thread", thread);
        return Json.stringify(requestBody);
    }

    private ObjectNode buildTitleValueJSON(String title, String value, Boolean shorten) {
        ObjectNode titleJSON = Json.newObject();
        titleJSON.put("title", title);
        titleJSON.put("value", value);
        titleJSON.put("short", shorten);
        return titleJSON;
    }

    private ObjectNode buildAttachmentJSON(String text, ArrayNode detailFields, EventType eventType) {
        ObjectNode attachmentsJSON = Json.newObject();
        attachmentsJSON.put("text", text);
        attachmentsJSON.put("fields", detailFields);
        String color = Play.application().configuration().getString("slack." + eventType, "");
        attachmentsJSON.put("color", color);
        return attachmentsJSON;
    }

    private ObjectNode buildSenderJSON(User sender) {
        ObjectNode senderJSON = Json.newObject();
        senderJSON.put("login", sender.loginId);
        senderJSON.put("id", sender.id);
        senderJSON.put("avatar_url", sender.avatarUrl());
        senderJSON.put("type", "User");
        senderJSON.put("site_admin", sender.isSiteManager());
        return senderJSON;
    }

    private ObjectNode buildPusherJSON(User sender) {
        ObjectNode pusherJSON = Json.newObject();
        pusherJSON.put("name", sender.name);
        pusherJSON.put("email", sender.email);
        return pusherJSON;
    }

    private ObjectNode buildRepositoryJSON() {
        ObjectNode repositoryJSON = Json.newObject();
        repositoryJSON.put("id", project.id);
        repositoryJSON.put("name", project.name);
        repositoryJSON.put("owner", project.owner);
        repositoryJSON.put("html_url", RouteUtil.getUrl(project));
        repositoryJSON.put("overview", project.overview);   // Description.
        repositoryJSON.put("private", project.isPrivate());
        return repositoryJSON;
    }

    private ObjectNode buildThreadJSON(Resource resource) {
        ObjectNode threadJSON = Json.newObject();
        WebhookThread webhookthread = WebhookThread.getWebhookThread(this.id, resource);
        if (webhookthread != null) {
            threadJSON.put("name", webhookthread.threadId);
        }
        return threadJSON;
    }

    private void sendRequest(String payload) {
        play.Logger.info(payload);
        try {
            WSRequestHolder requestHolder = WS.url(this.payloadUrl);
            if (StringUtils.isNotBlank(this.secret)) {
                requestHolder.setHeader("Authorization", String.format("token %s ", this.secret));
            }
            requestHolder
                    .setHeader("Content-Type", "application/json")
                    .setHeader("User-Agent", "Yobi-Hookshot")
                    .post(payload)
                    .map(
                            new Function<WSResponse, Integer>() {
                                public Integer apply(WSResponse response) {
                                    int statusCode = response.getStatus();
                                    String statusText = response.getStatusText();
                                    if (statusCode < 200 || statusCode >= 300) {
                                        // Unsuccessful status code - log some information in server.
                                        Logger.info(String.format("[Webhook] Request responded code  %d: %s", statusCode, statusText));
                                        Logger.info(String.format("[Webhook] Request payload: %s", payload));
                                    }
                                    return 0;
                                }
                            }
                    );
        } catch (Exception e) {
            // Request failed (Dead end point or invalid payload URL) - log some information in server.
            Logger.info("[Webhook] Request failed at given payload URL: " + this.payloadUrl);
        }
    }

    private void sendRequest(String payload, Long webhookId, Resource resource) {
        play.Logger.info(payload);
        try {
            WSRequestHolder requestHolder = WS.url(this.payloadUrl);
            if (StringUtils.isNotBlank(this.secret)) {
                requestHolder.setHeader("Authorization", String.format("token %s ", this.secret));
            }

            requestHolder
                    .setHeader("Content-Type", "application/json")
                    .setHeader("User-Agent", "Yobi-Hookshot")
                    .post(payload)
                    .map(
                            new Function<WSResponse, Integer>() {
                                public Integer apply(WSResponse response) {
                                    int statusCode = response.getStatus();
                                    String statusText = response.getStatusText();
                                    if (statusCode < 200 || statusCode >= 300) {
                                        // Unsuccessful status code - log some information in server.
                                        Logger.info(String.format("[Webhook] Request responded code  %d: %s", statusCode, statusText));
                                        Logger.info(String.format("[Webhook] Request payload: %s", payload));
                                    } else {
                                        WebhookThread webhookthread = WebhookThread.getWebhookThread(webhookId, resource);
                                        if (webhookthread == null) {
                                            String threadId = response.asJson().findPath("thread").findPath("name").asText();
                                            webhookthread = WebhookThread.create(webhookId, resource, threadId);
                                        }
                                    }
                                    return 0;
                                }
                            }
                    );
        } catch (Exception e) {
            // Request failed (Dead end point or invalid payload URL) - log some information in server.
            Logger.info("[Webhook] Request failed at given payload URL: " + this.payloadUrl);
        }
    }

    // Commit (message)
    public void sendRequestToPayloadUrl(List<RevCommit> commits, List<String> refNames, User sender, String title) {
        String requestBodyString = "";
        String requestMessage = buildRequestBody(commits, refNames, sender, title);
		play.Logger.warn(String.format("Commit Message >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>"));
		if (this.webhookType == WebhookType.TEAMS) {
			requestBodyString = buildRequestBodyTEAMS(commits, refNames, sender);
		} else { 
			requestBodyString = buildTextPropertyOnlyJSON(requestMessage);
		}
        sendRequest(requestBodyString);
    }

	// Commit (TEAMS)
	private String buildRequestBodyTEAMS(List<RevCommit> commits, List<String> refNames, User sender) {
		// 노드 선언
		ObjectNode templeteJson = Json.newObject();
		ObjectNode attachmentJson = Json.newObject();
		ObjectNode contentJson = Json.newObject();
		
		ObjectMapper attachmentInit = new ObjectMapper();
        ArrayNode attachmentsArray = attachmentInit.createArrayNode();
		
		ObjectMapper bodyInit = new ObjectMapper();
        ArrayNode bodyArray = bodyInit.createArrayNode();
		
		ObjectMapper containerInit = new ObjectMapper();
        ArrayNode containerArray = containerInit.createArrayNode();
		
		// 요소 입력
		templeteJson.put("type","message");
		templeteJson.put("attachments", attachmentsArray);
		attachmentsArray.add(attachmentJson);
		
		attachmentJson.put("contentType", "application/vnd.microsoft.card.adaptive");
		attachmentJson.put("contentUrl", "null");
		attachmentJson.put("content", contentJson);
		
		contentJson.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
		contentJson.put("type", "AdaptiveCard");
		contentJson.put("version", "1.2");
		contentJson.put("body", bodyArray);

		// 바디 등록
		bodyArray.add(BuildTitleTEAMS("코드 푸쉬"));		//제목
		for (RevCommit commit : commits) {
			bodyArray.add(BuildSubTitleTEAMS(commit, refNames.get(0)));	//내용 (작성자 + 상세내용)
        }
		
		return Json.stringify(templeteJson);
	}

    private String buildRequestBody(List<RevCommit> commits, List<String> refNames, User sender, String title) {
        StringBuilder requestMessage = new StringBuilder();
        requestMessage.append(Messages.get(Lang.defaultLang(), "notification.pushed.commits.to", project.name, commits.size(), refNames.get(0)));
        return requestMessage.toString();
    }

    // Commit (json)
    public void sendRequestToPayloadUrl(List<RevCommit> commits, List<String> refNames, User sender) {
        String requestBodyString = buildRequestBody(commits, refNames, sender);
        sendRequest(requestBodyString);
    }

    private String buildRequestBody(List<RevCommit> commits, List<String> refNames, User sender) {
        ObjectNode requestBody = Json.newObject();
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode refNamesNodes = mapper.createArrayNode();
        ArrayNode commitsNodes = mapper.createArrayNode();

        for (String refName : refNames) {
            refNamesNodes.add(refName);
        }

        requestBody.put("ref", refNamesNodes);

        for (RevCommit commit : commits) {
            commitsNodes.add(buildJSONFromCommit(project, commit));
        }

        requestBody.put("commits", commitsNodes);
        requestBody.put("head_commit", commitsNodes.get(0));
        requestBody.put("sender", buildSenderJSON(sender));
        requestBody.put("pusher", buildPusherJSON(sender));
        requestBody.put("repository", buildRepositoryJSON());

        return Json.stringify(requestBody);
    }

    private ObjectNode buildJSONFromCommit(Project project, RevCommit commit) {
        GitCommit gitCommit = new GitCommit(commit);
        ObjectNode commitJSON = Json.newObject();
        ObjectNode authorJSON = Json.newObject();
        ObjectNode committerJSON = Json.newObject();

        commitJSON.put("id", gitCommit.getFullId());
        commitJSON.put("message", gitCommit.getMessage());
        commitJSON.put("timestamp",
                new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZ").format(new Date(gitCommit.getCommitTime() * 1000L)));
        commitJSON.put("url", String.format("%s%s/commit/%s ", getBaseUrl(), RouteUtil.getUrl(project), gitCommit.getFullId()));

        authorJSON.put("name", gitCommit.getAuthorName());
        authorJSON.put("email", gitCommit.getAuthorEmail());
        committerJSON.put("name", gitCommit.getCommitterName());
        committerJSON.put("email", gitCommit.getCommitterEmail());
        // TODO : Add 'username' property (howto?)

        commitJSON.put("author", authorJSON);
        commitJSON.put("committer", committerJSON);

        // TODO : Add added, removed, modified file list (not supported by JGit?)

        return commitJSON;
    }

    @Override
    public String toString() {
        return "Webhook{" +
                "id=" + id +
                ", project=" + project +
                ", payloadUrl='" + payloadUrl + '\'' +
                ", secret='" + secret + '\'' +
                ", gitPush=" + gitPush +
                ", webhookType=" + webhookType +
                ", createdAt=" + createdAt +
                '}';
    }
	
	// make title teams (Common)
	private ObjectNode BuildTitleTEAMS(String eventType) {
		ObjectNode titleJson = Json.newObject();
		titleJson.put("type", "TextBlock");
		titleJson.put("size", "Medium");
		titleJson.put("weight", "Bolder");
		titleJson.put("text", String.format("[%s] %s", eventType, project.name));
		
		return titleJson;
	}

	// make subtitle teams (issue)
	private ObjectNode BuildSubTitleTEAMS(EventType eventType, User sender, Issue eventIssue) {
		// uri
		String eventIssueUrl = controllers.routes.IssueApp.issue(eventIssue.project.owner, eventIssue.project.name, eventIssue.getNumber()).url();
        String uri = buildRequestMessage(eventIssueUrl, String.format("#%d: %s", eventIssue.number, eventIssue.title));
		
		// Container Json 구성
		ObjectMapper containerInit = new ObjectMapper();
        ArrayNode containerItems = containerInit.createArrayNode();		
		
		ObjectMapper columnSetColumnsInit = new ObjectMapper();
        ArrayNode columnSetColumns = columnSetColumnsInit.createArrayNode();			
		
		ObjectNode containerJson = Json.newObject();
		containerJson.put("type", "Container");
		containerJson.put("separator", true);
		containerJson.put("items", containerItems);
		
		ObjectNode columnSetJson = Json.newObject();
		columnSetJson.put("type", "ColumnSet");
		columnSetJson.put("columns", columnSetColumns);
		
		containerItems.add(columnSetJson);
		
		// Container Json 구성 요소
		ObjectMapper columnItemsInit = new ObjectMapper();
        ArrayNode columnItems = columnItemsInit.createArrayNode();		
		
		ObjectNode columnJson = Json.newObject();
		columnJson.put("type", "Column");
		columnJson.put("items", columnItems);
		
		// 작성자 정보
		ObjectNode titleJson = Json.newObject();
		titleJson.put("type", "TextBlock");
		titleJson.put("weight", "Bolder");
		titleJson.put("text", String.format("%s (%s) ", sender.name, sender.email));
		titleJson.put("wrap", true);
		
		// 작성 시간
		ObjectNode subTitleJson = Json.newObject();
		subTitleJson.put("type", "TextBlock");
		subTitleJson.put("spacing", "None");
		subTitleJson.put("text", String.format("%s", new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(eventIssue.updatedDate)));
		subTitleJson.put("isSubtle", true);
		subTitleJson.put("color", "Dark");
		subTitleJson.put("size", "Small");
		subTitleJson.put("wrap", true);
		
		columnItems.add(titleJson);
		columnItems.add(subTitleJson);
		
		// 상세 내용
		ObjectMapper factsInit = new ObjectMapper();
        ArrayNode facts = factsInit.createArrayNode();		
		
		ObjectNode factSetJson = Json.newObject();
		factSetJson.put("type", "FactSet");
		factSetJson.put("facts", facts);
		
		ObjectNode detailTitle = Json.newObject();
		detailTitle.put("title", "제목");
		detailTitle.put("value", uri);
		
		ObjectNode detailContent = Json.newObject();
		switch (eventType) {
			case ISSUE_ASSIGNEE_CHANGED:
				detailContent.put("title", "담당자");
				detailContent.put("value", eventIssue.assignee.user.name);
				break;
			default:
				detailContent.put("title", "내용");
				detailContent.put("value", replaceLineChar(eventIssue.body));
		}
		
		facts.add(detailTitle);
		facts.add(detailContent);
		
		columnSetColumns.add(columnJson);	// 작성자 정보
		containerItems.add(factSetJson);	// 상세 내용
		
		return containerJson;
	}

	// make subtitle teams (posting)
	private ObjectNode BuildSubTitleTEAMS(User sender,  Posting eventPost) {
		// uri
		String eventPostUrl = RouteUtil.getUrl(eventPost);
        String uri = buildRequestMessage(eventPostUrl, String.format("#%d: %s", eventPost.number, eventPost.title));
		
		// Container Json 구성
		ObjectMapper containerInit = new ObjectMapper();
        ArrayNode containerItems = containerInit.createArrayNode();		
		
		ObjectMapper columnSetColumnsInit = new ObjectMapper();
        ArrayNode columnSetColumns = columnSetColumnsInit.createArrayNode();			
		
		ObjectNode containerJson = Json.newObject();
		containerJson.put("type", "Container");
		containerJson.put("separator", true);
		containerJson.put("items", containerItems);
		
		ObjectNode columnSetJson = Json.newObject();
		columnSetJson.put("type", "ColumnSet");
		columnSetJson.put("columns", columnSetColumns);
		
		containerItems.add(columnSetJson);
		
		// Container Json 구성 요소
		ObjectMapper columnItemsInit = new ObjectMapper();
        ArrayNode columnItems = columnItemsInit.createArrayNode();		
		
		ObjectNode columnJson = Json.newObject();
		columnJson.put("type", "Column");
		columnJson.put("items", columnItems);
		
		// 작성자 정보
		ObjectNode titleJson = Json.newObject();
		titleJson.put("type", "TextBlock");
		titleJson.put("weight", "Bolder");
		titleJson.put("text", String.format("%s < %s > ", sender.name, sender.email));
		titleJson.put("wrap", true);
		
		// 작성 시간
		ObjectNode subTitleJson = Json.newObject();
		subTitleJson.put("type", "TextBlock");
		subTitleJson.put("spacing", "None");
		subTitleJson.put("text", String.format("%s", new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(eventPost.updatedDate)));
		subTitleJson.put("isSubtle", true);
		subTitleJson.put("color", "Dark");
		subTitleJson.put("size", "Small");
		subTitleJson.put("wrap", true);
		
		columnItems.add(titleJson);
		columnItems.add(subTitleJson);
		
		// 상세 내용
		ObjectMapper factsInit = new ObjectMapper();
        ArrayNode facts = factsInit.createArrayNode();		
		
		ObjectNode factSetJson = Json.newObject();
		factSetJson.put("type", "FactSet");
		factSetJson.put("facts", facts);
		
		ObjectNode detailTitle = Json.newObject();
		detailTitle.put("title", "제목");
		detailTitle.put("value", uri);
		
		ObjectNode detailContent = Json.newObject();
		detailContent.put("title", "내용");
		detailContent.put("value", eventPost.body);
		
		facts.add(detailTitle);
		facts.add(detailContent);
		
		columnSetColumns.add(columnJson);	// 작성자 정보
		containerItems.add(factSetJson);	// 상세 내용
		
		return containerJson;
	}

	// make subtitle teams (comment)
	private ObjectNode BuildSubTitleTEAMS(User sender,  Comment eventComment) {
		// uri
		String eventCommentUrl = RouteUtil.getUrl(eventComment);
		String uri = buildRequestMessage(eventCommentUrl, String.format("#%d: %s", eventComment.getParent().number, eventComment.getParent().title));
		
		// Container Json 구성
		ObjectMapper containerInit = new ObjectMapper();
        ArrayNode containerItems = containerInit.createArrayNode();		
		
		ObjectMapper columnSetColumnsInit = new ObjectMapper();
        ArrayNode columnSetColumns = columnSetColumnsInit.createArrayNode();			
		
		ObjectNode containerJson = Json.newObject();
		containerJson.put("type", "Container");
		containerJson.put("separator", true);
		containerJson.put("items", containerItems);
		
		ObjectNode columnSetJson = Json.newObject();
		columnSetJson.put("type", "ColumnSet");
		columnSetJson.put("columns", columnSetColumns);
		
		containerItems.add(columnSetJson);
		
		// Container Json 구성 요소
		ObjectMapper columnItemsInit = new ObjectMapper();
        ArrayNode columnItems = columnItemsInit.createArrayNode();		
		
		ObjectNode columnJson = Json.newObject();
		columnJson.put("type", "Column");
		columnJson.put("items", columnItems);
		
		// 작성자 정보
		ObjectNode titleJson = Json.newObject();
		titleJson.put("type", "TextBlock");
		titleJson.put("weight", "Bolder");
		titleJson.put("text", String.format("%s < %s > ", sender.name, sender.email));
		titleJson.put("wrap", true);
		
		// 작성 시간
		ObjectNode subTitleJson = Json.newObject();
		subTitleJson.put("type", "TextBlock");
		subTitleJson.put("spacing", "None");
		subTitleJson.put("text", String.format("%s", new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(eventComment.createdDate)));
		subTitleJson.put("isSubtle", true);
		subTitleJson.put("color", "Dark");
		subTitleJson.put("size", "Small");
		subTitleJson.put("wrap", true);
		
		columnItems.add(titleJson);
		columnItems.add(subTitleJson);
		
		// 상세 내용
		ObjectMapper factsInit = new ObjectMapper();
        ArrayNode facts = factsInit.createArrayNode();		
		
		ObjectNode factSetJson = Json.newObject();
		factSetJson.put("type", "FactSet");
		factSetJson.put("facts", facts);
		
		ObjectNode detailTitle = Json.newObject();
		detailTitle.put("title", "제목");
		detailTitle.put("value", uri);
		
		ObjectNode detailContent = Json.newObject();
		detailContent.put("title", "내용");
		detailContent.put("value", eventComment.contents);
		
		facts.add(detailTitle);
		facts.add(detailContent);
		
		columnSetColumns.add(columnJson);	// 작성자 정보
		containerItems.add(factSetJson);	// 상세 내용
		
		return containerJson;
	}
	
	// make subtitle teams (commit)
	private ObjectNode BuildSubTitleTEAMS(RevCommit commit, String branch) {
		GitCommit gitCommit = new GitCommit(commit);
		
		// Container Json 구성
		ObjectMapper containerInit = new ObjectMapper();
        ArrayNode containerItems = containerInit.createArrayNode();		
		
		ObjectMapper columnSetColumnsInit = new ObjectMapper();
        ArrayNode columnSetColumns = columnSetColumnsInit.createArrayNode();			
		
		ObjectNode containerJson = Json.newObject();
		containerJson.put("type", "Container");
		containerJson.put("separator", true);
		containerJson.put("items", containerItems);
		
		ObjectNode columnSetJson = Json.newObject();
		columnSetJson.put("type", "ColumnSet");
		columnSetJson.put("columns", columnSetColumns);
		
		containerItems.add(columnSetJson);
		
		// Container Json 구성 요소
		ObjectMapper columnItemsInit = new ObjectMapper();
        ArrayNode columnItems = columnItemsInit.createArrayNode();		
		
		ObjectNode columnJson = Json.newObject();
		columnJson.put("type", "Column");
		columnJson.put("items", columnItems);
		
		// 작성자 정보
		ObjectNode titleJson = Json.newObject();
		titleJson.put("type", "TextBlock");
		titleJson.put("weight", "Bolder");
		titleJson.put("text", String.format("%s < %s > ", gitCommit.getCommitterName(), gitCommit.getCommitterEmail()));
		titleJson.put("wrap", true);
		
		// 작성 시간
		ObjectNode subTitleJson = Json.newObject();
		subTitleJson.put("type", "TextBlock");
		subTitleJson.put("spacing", "None");
		subTitleJson.put("text", String.format("%s", new SimpleDateFormat("yyyy-MM-dd HH:mm:ssZ").format(new Date(gitCommit.getCommitTime() * 1000L))));
		subTitleJson.put("isSubtle", true);
		subTitleJson.put("color", "Dark");
		subTitleJson.put("size", "Small");
		subTitleJson.put("wrap", true);
		
		columnItems.add(titleJson);
		columnItems.add(subTitleJson);
		
		// 상세 내용
		ObjectMapper factsInit = new ObjectMapper();
        ArrayNode facts = factsInit.createArrayNode();		
		
		ObjectNode factSetJson = Json.newObject();
		factSetJson.put("type", "FactSet");
		factSetJson.put("facts", facts);
		
		ObjectNode detailHash = Json.newObject();
		detailHash.put("title", "해시");
		detailHash.put("value", String.format("[%s] [@%s](%s%s/commit/%s)", branch.substring(5), 
		gitCommit.getFullId().substring(0, 8), getBaseUrl(), RouteUtil.getUrl(project), gitCommit.getFullId()));
		
		ObjectNode detailTitle = Json.newObject();
		detailTitle.put("title", "제목");
		detailTitle.put("value", gitCommit.getShortMessage());
		
		ObjectNode detailContent = Json.newObject();
		detailContent.put("title", "내용");
		detailContent.put("value", getCommitMessage(gitCommit.getMessage()));
		
		facts.add(detailHash);
		facts.add(detailTitle);
		facts.add(detailContent);
		
		columnSetColumns.add(columnJson);	// 작성자 정보
		containerItems.add(factSetJson);	// 상세 내용
		
		return containerJson;
	}

	private String replaceLineChar(String str)
	{
		String result = str.replace("\r\n", "   \n");
		
		return result;
	}
	
	private String getCommitMessage(String str)
	{
		
		String result = str.substring(str.indexOf("\n\n") + 1);
		result = result.replace("\n", "   \n");
		
		return result;
	}
}


