package com.akto.interceptor;

import com.akto.audit_logs_util.AuditLogsUtil;
import com.akto.dao.RBACDao;
import com.akto.dao.audit_logs.ApiAuditLogsDao;
import com.akto.dao.billing.OrganizationsDao;
import com.akto.dao.context.Context;
import com.akto.dto.User;
import com.akto.dto.audit_logs.ApiAuditLogs;
import com.akto.dto.billing.FeatureAccess;
import com.akto.dto.billing.Organization;
import com.akto.dto.RBAC.Role;
import com.akto.dto.rbac.RbacEnums;
import com.akto.dto.rbac.RbacEnums.Feature;
import com.akto.dto.rbac.RbacEnums.ReadWriteAccess;
import com.akto.filter.UserDetailsFilter;
import com.akto.log.LoggerMaker;
import com.akto.log.LoggerMaker.LogDb;
import com.akto.runtime.policies.UserAgentTypePolicy;
import com.akto.util.DashboardMode;
import com.mongodb.client.model.Filters;
import com.opensymphony.xwork2.Action;
import com.opensymphony.xwork2.ActionInvocation;
import com.opensymphony.xwork2.ActionSupport;
import com.opensymphony.xwork2.interceptor.AbstractInterceptor;
import org.apache.struts2.ServletActionContext;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RoleAccessInterceptor extends AbstractInterceptor {

    private static final LoggerMaker loggerMaker = new LoggerMaker(RoleAccessInterceptor.class, LoggerMaker.LogDb.DASHBOARD);

    String featureLabel;
    String accessType;
    String actionDescription;

    public void setFeatureLabel(String featureLabel) {
        this.featureLabel = featureLabel;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
    }

    public void setActionDescription(String actionDescription) {
        this.actionDescription = actionDescription;
    }

    public final static String FORBIDDEN = "FORBIDDEN";
    private final static String USER = "user";
    private final static String FEATURE_LABEL_STRING = "RBAC_FEATURE";

    private boolean checkForPaidFeature(int accountId){
        Organization organization = OrganizationsDao.instance.findOne(Filters.in(Organization.ACCOUNTS, accountId));
        if(organization == null || organization.getFeatureWiseAllowed() == null || organization.getFeatureWiseAllowed().isEmpty()){
            return true;
        }

        HashMap<String, FeatureAccess> featureWiseAllowed = organization.getFeatureWiseAllowed();
        FeatureAccess featureAccess = featureWiseAllowed.getOrDefault(FEATURE_LABEL_STRING, FeatureAccess.noAccess);
        return featureAccess.getIsGranted();
    }

    private int getUserAccountId (Map<String, Object> session) throws Exception{
        try {
            Object accountIdObj = session.get(UserDetailsFilter.ACCOUNT_ID);
            String accountIdStr = accountIdObj == null ? null : accountIdObj+"";
            if(accountIdStr == null){
                throw new Exception("found account id as null in interceptor");
            }
            int accountId = Integer.parseInt(accountIdStr);
            return accountId;
        } catch (Exception e) {
            throw new Exception("unable to parse account id: " + e.getMessage());
        }
    }

    @Override
    public String intercept(ActionInvocation invocation) throws Exception {
        ApiAuditLogs apiAuditLogs = null;
        try {
            HttpServletRequest request = ServletActionContext.getRequest();

            if(featureLabel == null) {
                throw new Exception("Feature list is null or empty");
            }

            Map<String, Object> session = invocation.getInvocationContext().getSession();
            if(session == null){
                throw new Exception("Found session null, returning from interceptor");
            }
            loggerMaker.infoAndAddToDb("Found session in interceptor.", LogDb.DASHBOARD);
            User user = (User) session.get(USER);

            if(user == null) {
                throw new Exception("User not found in session, returning from interceptor");
            }
            int sessionAccId = getUserAccountId(session);

            if(!DashboardMode.isMetered()){
                return invocation.invoke();
            }

            if(!(checkForPaidFeature(sessionAccId) || featureLabel.equalsIgnoreCase(RbacEnums.Feature.ADMIN_ACTIONS.toString()))){
                return invocation.invoke();
            }

            loggerMaker.infoAndAddToDb("Found user in interceptor: " + user.getLogin(), LogDb.DASHBOARD);

            int userId = user.getId();

            Role userRoleRecord = RBACDao.getCurrentRoleForUser(userId, sessionAccId);
            String userRole = userRoleRecord != null ? userRoleRecord.getName().toUpperCase() : "";

            if(userRole == null || userRole.isEmpty()) {
                throw new Exception("User role not found");
            }
            Feature featureType = Feature.valueOf(this.featureLabel.toUpperCase());

            ReadWriteAccess accessGiven = userRoleRecord.getReadWriteAccessForFeature(featureType);
            boolean hasRequiredAccess = false;

            if(this.accessType.equalsIgnoreCase(ReadWriteAccess.READ.toString()) || this.accessType.equalsIgnoreCase(accessGiven.toString())){
                hasRequiredAccess = true;
            }
            if(featureLabel.equals(Feature.ADMIN_ACTIONS.name())){
                hasRequiredAccess = userRole.equals(Role.ADMIN.name());
            }

            if(!hasRequiredAccess) {
                ((ActionSupport) invocation.getAction()).addActionError("The role '" + userRole + "' does not have access.");
                return FORBIDDEN;
            }

            try {
                if (this.accessType.equalsIgnoreCase(ReadWriteAccess.READ_WRITE.toString())) {
                    long timestamp = Context.now();
                    String apiEndpoint = invocation.getProxy().getActionName();
                    String actionDescription = this.actionDescription == null ? "Error: Description not available" : this.actionDescription;
                    String userEmail = user.getLogin();
                    String userAgent = request.getHeader("User-Agent") == null ? "Unknown User-Agent" : request.getHeader("User-Agent");
                    UserAgentTypePolicy.ClientType userAgentType = UserAgentTypePolicy.findUserAgentType(userAgent);
                    List<String> userProxyIpAddresses = AuditLogsUtil.getClientIpAddresses(request);
                    String userIpAddress = userProxyIpAddresses.get(0);

                    apiAuditLogs = new ApiAuditLogs(timestamp, apiEndpoint, actionDescription, userEmail, userAgentType.name(), userIpAddress, userProxyIpAddresses);
                }
            } catch(Exception e) {
                loggerMaker.errorAndAddToDb(e, "Error while inserting api audit logs: " + e.getMessage(), LogDb.DASHBOARD);
            }

        } catch(Exception e) {
            String api = invocation.getProxy().getActionName();
            String error = "Error in RoleInterceptor for api: " + api + " ERROR: " + e.getMessage();
            loggerMaker.errorAndAddToDb(e, error, LoggerMaker.LogDb.DASHBOARD);
        }

        String result = invocation.invoke();

        if (apiAuditLogs != null && result.equalsIgnoreCase(Action.SUCCESS.toUpperCase())) {
            ApiAuditLogsDao.instance.insertOne(apiAuditLogs);
        }

        return result;
    }
}
