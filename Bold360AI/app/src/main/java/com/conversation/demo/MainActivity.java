package com.conversation.demo;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.nanorep.convesationui.structure.FriendlyDatestampFormatFactory;
import com.nanorep.convesationui.structure.controller.ChatController;
import com.nanorep.convesationui.structure.controller.ChatEventListener;
import com.nanorep.convesationui.structure.controller.ChatLoadResponse;
import com.nanorep.convesationui.structure.controller.ChatLoadedListener;
import com.nanorep.convesationui.structure.handlers.AccountInfoProvider;
import com.nanorep.convesationui.structure.handlers.ChatHandler;
import com.nanorep.convesationui.structure.history.FetchDirection;
import com.nanorep.convesationui.structure.history.HistoryListener;
import com.nanorep.convesationui.structure.history.HistoryProvider;
import com.nanorep.nanoengine.Account;
import com.nanorep.nanoengine.AccountInfo;
import com.nanorep.nanoengine.BotAccount;
import com.nanorep.nanoengine.Entity;
import com.nanorep.nanoengine.NRConversationMissingEntities;
import com.nanorep.nanoengine.PersonalInfoRequest;
import com.nanorep.nanoengine.Property;
import com.nanorep.nanoengine.chatelement.ChatElement;
import com.nanorep.nanoengine.chatelement.StorableChatElement;
import com.nanorep.nanoengine.model.configuration.ConversationSettings;
import com.nanorep.nanoengine.model.configuration.TimestampStyle;
import com.nanorep.nanoengine.model.conversation.statement.IncomingStatement;
import com.nanorep.nanoengine.model.conversation.statement.OnStatementResponse;
import com.nanorep.nanoengine.model.conversation.statement.StatementRequest;
import com.nanorep.nanoengine.model.conversation.statement.StatementResponse;
import com.nanorep.nanoengine.nonbot.EntitiesProvider;
import com.nanorep.sdkcore.model.ChatStatement;
import com.nanorep.sdkcore.model.StatementScope;
import com.nanorep.sdkcore.model.StatementStatus;
import com.nanorep.sdkcore.model.SystemStatement;
import com.nanorep.sdkcore.utils.Completion;
import com.nanorep.sdkcore.utils.Event;
import com.nanorep.sdkcore.utils.EventListener;
import com.nanorep.sdkcore.utils.NRError;
import com.nanorep.sdkcore.utils.UtilityMethodsKt;
import com.nanorep.sdkcore.utils.network.ConnectivityReceiver;

import org.jetbrains.anko.ToastsKt;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;

import static com.nanorep.sdkcore.model.StatementModels.StatusPending;
import static com.nanorep.sdkcore.utils.UtilityMethodsKt.getPx;
import static com.nanorep.sdkcore.utils.UtilityMethodsKt.ifNotNull;


public class MainActivity extends AppCompatActivity implements
        AccountsListAdapter.AccountsListListener,
        ConnectivityReceiver.ConnectivityListener, ChatEventListener {

    public static final String CONVERSATION_FRAGMENT_TAG = "conversation_fragment";
    public static final String END_HANDOVER_SESSION = "bye bye handover";

    public static final int HistoryPageSize = 8;
    private int handoverReplyCount = 0;

    private ProgressBar progressBar;
    private ImageButton startButton;

    private EditText accountNameEditText;
    private EditText knowledgeBaseEditText;
    private EditText apiKeyEditText;
    private EditText serverEditText;

    private ConcurrentLinkedQueue<StatementRequest> failedStatements = new ConcurrentLinkedQueue<>();

    private Map<String, AccountInfo> accounts = new HashMap<>();

    private ConnectivityReceiver connectivityReceiver = new ConnectivityReceiver();

    /**
     * in use when previously failed statements are posted to indicate if the connection is done again
     * to stop the re-posting.
     */
    private boolean connectionOk = true;

    /**
     * indicates if we wait for conversation creation.
     * statements at this time are collected on the SDK, engine, side.
     * once a connection established, the app should activate the
     * createConversation API.
     * while requests are posted if the engine indicates that the conversation is not available, it
     * tries to create it. once creation succeeded the app will get a call to "onConversationIdUpdated"
     * and this field will be cleared.
     */
    private boolean pendingConversationCreation = false;

    private ChatController chatController;
    private MyHistoryProvider historyProvider;
    private AccountInfo lastSelectedAccount;

    /*!! ChatController's providers kept as members to make sure their object will be kept alive
       (chatController handles those listeners and providers as weak references, which means they
       may be released otherwise) */
    private MyAccountInfoProvider accountInfoProvider;
    private AppChatUIProvider appChatUIProvider;
    private MyEntitiesProvider entitiesProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        historyProvider = new MyHistoryProvider();

        progressBar = findViewById(R.id.progressBar);

        startButton = findViewById(R.id.startButton);

        accountNameEditText = findViewById(R.id.account_name_edit_text);

        knowledgeBaseEditText = findViewById(R.id.knowledgebase_edit_text);

        apiKeyEditText = findViewById(R.id.api_key_edit_text);

        serverEditText = findViewById(R.id.server_edit_text);

    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // clears all resources
        connectivityReceiver.unregister(this);

        try {
            chatController.destruct();
        } catch (Exception NRInitilizationException) {
            Log.e(CONVERSATION_FRAGMENT_TAG, "NanoRep was not initialized");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        connectivityReceiver.register(this, this);
    }

    private BotAccount getAccount() {
        String accountName = accountNameEditText.getText().toString();
        String kb = knowledgeBaseEditText.getText().toString();
        String apiKey = apiKeyEditText.getText().toString();
        String server = serverEditText.getText().toString();

        return new BotAccount(apiKey, accountName,
                kb, server, null);
    }



    private void onAccountChanged() {
        pendingConversationCreation = false;
        ifNotNull(failedStatements, new Function0<Unit>() {
            @Override
            public Unit invoke() {
                failedStatements.clear();
                return null;
            }
        });
        failedStatements.clear();
    }

    @Override
    public void onUserAccountSelected(Account account) {
        if(lastSelectedAccount != null && !account.equals(lastSelectedAccount)){
            onAccountChanged();
        }

        lastSelectedAccount = account;
        if (!accounts.containsKey(account.apiKey())) {
            accounts.put(account.apiKey(), account);
        }
        historyProvider.accountId = account.getApiKey();

        if (account instanceof BotAccount) {
            setBotAccount((BotAccount) account);
        }

        // !- account should already include the last conversation, there is no need to fetch it
        //account.updateInfo(fetchAccountConversationData(account.getAccount()));

        this.chatController = createChat(account);
    }

    public void onDefaultAccountDetails(View view) {
        accountNameEditText.setText(getResources().getString(R.string.account_name));
        knowledgeBaseEditText.setText(getResources().getString(R.string.knowledge_base));
        apiKeyEditText.setText(getResources().getString(R.string.api_key));
        serverEditText.setText(getResources().getString(R.string.server));
    }

    public void onStartClicked(View view){
        View focused = getCurrentFocus();

        if (focused != null) {
            focused.clearFocus();
        }

        progressBar.setVisibility(View.VISIBLE);
        startButton.setEnabled(false);

        onUserAccountSelected(getAccount());
    }

    private void setBotAccount(BotAccount account) {
        Map<String, String> conversationContext = new HashMap<>();
        account.setContexts(conversationContext);
        account.setEntities(new String[]{"SUBSCRIBERS"});
    }

    @NonNull
    private ChatController createChat(Account account) {

        ConversationSettings settings = new ConversationSettings().disableFeedback()
                .speechEnable(true)
                .enableMultiRequestsOnLiveAgent(true)
                .setReadMoreThreshold(300)
                .timestampConfig(true, new TimestampStyle("hh:mm aa",
                        getPx(11), Color.parseColor("#33aa33"), null) )
                .enableOfflineMultiRequests(true) // defaults to true
                .datestamp(true, new FriendlyDatestampFormatFactory(this));

        //-> to configure the default text configuration to all bubbles text add something like the following:
        //settings.textStyleConfig(new StyleConfig(getPx(23), Color.BLUE, null));

        accountInfoProvider = new MyAccountInfoProvider();
        appChatUIProvider = new AppChatUIProvider();
        entitiesProvider = new MyEntitiesProvider();

        return new ChatController.Builder(this)
                .conversationSettings(settings)
                .entitiesProvider(entitiesProvider)
                .chatEventListener(this)
                .historyProvider(historyProvider)
                .accountProvider(accountInfoProvider)
                .chatUIProvider(appChatUIProvider)
                .chatHandoverHandler(new MyHandoverHandler())
                .build(account, new ChatLoadedListener(){

                    // TODO: !! should be activated on main thread by the calling component (chatController)
                    @Override
                    public void onComplete(ChatLoadResponse result) {

						//fixme: check error type and decide if chat can be opened (not only on success)
                        NRError error = result.getError();

                        if(error != null) {
                            onError(error);
                            if (! (error.isConversationError() || error.isServerConnectionNotAvailable())) {
                                openConversationFragment(result.getFragment());
                            }

                        } else {
                            openConversationFragment(result.getFragment());
                        }
                        progressBar.setVisibility(View.INVISIBLE);
                    }
                });
    }

    @Override
    public void onAccountUpdate(@NonNull AccountInfo accountInfo) {
        AccountInfo savedAccount = getAccountInfo(accountInfo.getApiKey());
        if(savedAccount != null) {
            savedAccount.updateInfo(accountInfo.getInfo());
        } else {
            Log.w(CONVERSATION_FRAGMENT_TAG, "Got account update for account that is currently not " +
                    "in accounts list\nadding account to saved accounts list");
            accounts.put(accountInfo.getApiKey(), accountInfo);
        }
    }

    private void openConversationFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();

        if(fragmentManager == null || fragmentManager.isStateSaved() ||
                fragmentManager.findFragmentByTag(CONVERSATION_FRAGMENT_TAG) != null) return;

        fragmentManager.beginTransaction()
                .replace(R.id.content_main, fragment, CONVERSATION_FRAGMENT_TAG)
                .addToBackStack(null)
                .commit();
    }

    @Override
    public void onChatStarted() {

        progressBar.setVisibility(View.GONE);

        /*An example for request injection on conversation load:
          chatController.post(new SystemStatement("Hello, sample response injection on load")); */

    }

    // *** Missing entities and personal info examples ***

    // Example for Entity creation
    private Entity createEntity(String entityName) {
        Entity entity = new Entity(Entity.PERSISTENT, Entity.NUMBER, "3", entityName, "1");
        for (int i = 0; i < 3; i++) {
            Property property = new Property(Entity.NUMBER, String.valueOf(i) + "234", "SUBSCRIBER");
            property.setName(property.getValue());
            property.addProperty(new Property(Entity.NUMBER, String.valueOf(i) + "234", "ID"));
            entity.addProperty(property);
        }
        return entity;
    }

    class MyEntitiesProvider implements EntitiesProvider {

        // handle missing entities
        @Override
        public void provide(@NonNull ArrayList<String> entities, @NonNull Completion<ArrayList<Entity>> onReady) {
            NRConversationMissingEntities missingEntities = new NRConversationMissingEntities();
            for (String missingEntity : entities) {
                if (missingEntity.equals("SUBSCRIBERS")) {
                    missingEntities.addEntity(createEntity(missingEntity));
                    break;

                }
            }

            onReady.onComplete(new ArrayList<Entity>(missingEntities.getEntities()));
        }

        // handle personal information
        @Override
        public void provide(@NotNull PersonalInfoRequest personalInfoRequest, @NotNull PersonalInfoRequest.Callback callback) {
            switch (personalInfoRequest.getId()) {
                case "balance":
                    String balance = String.format("%10.2f$", Math.random() * 10000);
                    callback.onInfoReady(balance, null);
                    return;
            }

            callback.onInfoReady("1,000$", null);
        }
    }

    class MyAccountInfoProvider implements AccountInfoProvider {

        @Override
        public void updateAccountInfo(@NonNull AccountInfo accountInfo) {
            AccountInfo savedAccount = getAccountInfo(accountInfo.getApiKey());
            if(savedAccount != null){
                savedAccount.updateInfo(accountInfo.getInfo());
            } else {
                accounts.put(accountInfo.getApiKey(), accountInfo);
            }

        }

        @Override
        public void provide(@NonNull String apiKey, @NonNull Completion<AccountInfo> callback) {
            AccountInfo savedAccount = getAccountInfo(apiKey);

            callback.onComplete(savedAccount);
        }
    }

    private AccountInfo getAccountInfo(String apiKey) {
        return accounts.get(apiKey);
    }


    @SuppressLint("ResourceType")
    @Override
    public void onError(@NonNull NRError error) {
        progressBar.setVisibility(View.INVISIBLE);
        String reason = error.getReason();

        switch (error.getErrorCode()){

            case NRError.ConversationCreationError:

                notifyConversationError(error);

                pendingConversationCreation = true;

                if(reason!=null && reason.equals(NRError.ConnectionException)) {
                    notifyConnectionError();
                }

                //fixme: change the following lines to match the start button (add disable on click and enable as done here)

                if(startButton != null){
                    startButton.setEnabled(true);
                }
                break;

            case NRError.StatementError:

                if(error.isConversationError()){
                    notifyConversationError(error);
                    pendingConversationCreation = true;

                } else {
                    notifyStatementError(error);
                }

                StatementRequest statementRequest = (StatementRequest) error.getData();
                if(isPendableError(error) && statementRequest != null) {

                    Log.d(CONVERSATION_FRAGMENT_TAG, "error: "+error.getReason()+", adding " + statementRequest.getStatement() + " to app pending");
                    failedStatements.add(statementRequest);
                } else {
                    chatController.post(new SystemStatement("Something went wrong. Please try another request"));
                }
                break;

            default:
                /*all other errors will be handled here. Demo implementation, displays a toast and
                  writes to the log.
                 if needed the error.getErrorCode() and sometimes the error.getReason() can provide
                 the details regarding the error
                 */
                Log.e("App-ERROR", error.toString());

                if(reason != null && reason.equals(NRError.ConnectionException)) {
                    notifyConnectionError();
                } else {
                    notifyError(error, "general error: ", Color.DKGRAY);
                }
        }
    }
    
    private void notifyConnectionError() {
        ToastsKt.toast(this, "Connection failure.\nPlease check your connection.");
    }

    private boolean isPendableError(NRError error){
        String reason = error.getReason();
        if(reason == null){
            reason = error.getDescription();
        }
        return reason != null && (reason.equals(NRError.ConnectionException) ||
                reason.equals(NRError.ConversationNotFound) ||
                reason.equals(NRError.IllegalStateError));// in case the request was posted before the fragment was loaded.
    }

    private void notifyConversationError(@NonNull NRError error) {
        notifyError(error, "Conversation is not available: ", Color.parseColor("#6666aa"));
    }

    private void notifyStatementError(@NonNull NRError error) {
        notifyError(error, "statement failure - ", Color.RED);
    }

    @SuppressLint("ResourceType")
    private void notifyError(@NonNull NRError error, String s, int i) {

        // this notification will not be visible

        try {List<Fragment> fragmentList = getSupportFragmentManager().getFragments();
            View snackView = fragmentList.get(fragmentList.size()-1).getView();

            if(snackView != null) {
                UtilityMethodsKt.snack(snackView,
                        s + error.getReason() + ": " + error.getDescription(),
                        4000, -1, Gravity.CENTER, new int[]{}, i);
            }
        } catch (Exception ignored) {
            ToastsKt.toast(this, s + error.getReason() + ": " + error.getDescription());
        }
    }

    public void onInitializeChatHandover() {
        handoverReplyCount = 0;
        if(!connectionOk){
            chatController.endHandover();
            // response scope should be provided indicate the chat agent status while statement were injected.
            // (see StatementScope for available scopes)
            chatController.post(new SystemStatement("Failed to initiate live chat, please check your internet connection"));
        } else {
            chatController.post(new IncomingStatement("Hey, my name is Bob, how can I help?",
                    StatementScope.HandoverScope()));
        }
    }

    private boolean isEndHandover(String inputText) {
        return inputText != null && inputText.equalsIgnoreCase(END_HANDOVER_SESSION);
    }

    public void onChatHandoverInput(@NonNull StatementRequest statementRequest) {
        if(chatController == null) return;

        OnStatementResponse inputCallback = statementRequest.getCallback();

        boolean endHandover = isEndHandover(statementRequest.getText());

        // in case request to live agent can't be delivered (in our demo- connection failure)
        if(!connectionOk){
            // pass live agent request error indication to the chat SDK
            if(inputCallback != null) {
                inputCallback.onError(new NRError(NRError.LiveStatementError, NRError.ConnectionException, statementRequest));
            }
            failedStatements.add(statementRequest);
        } else {
            passHandoverResponse(statementRequest, endHandover);
        }

        // verify handover is still on to prevent requests from being handled as handover.
        if(endHandover) {
            chatController.endHandover();
        }
    }

    private void passHandoverResponse(@NonNull StatementRequest statementRequest, boolean endHandover) {

        String responseText = "";

        if (endHandover) {
            responseText = "Bye - Handover complete";

        } else {
            //!- for long text test:
            String longText = "John Perry did two things on his 75th birthday. First, he visited his wife's grave. Then he joined the army.\n" +
                    "The good news is that humanity finally made it into interstellar space. The bad news is that planets fit to live on are scarce - and alien races willing to fight us for them are common. So, we fight, to defend Earth and to stake our own claim to planetary real estate. Far from Earth, the war has been going on for decades: brutal, bloody, unyielding.\n" +
                    "\n" +
                    "Earth itself is a backwater. The bulk of humanity's resources are in the hands of the Colonial Defense Force. Everybody knows that when you reach retirement age, you can join the CDF. They don't want young people; they want people who carry the knowledge and skills of decades of living. You'll be taken off Earth and never allowed to return. You'll serve two years at the front. And if you survive, you'll be given a generous homestead stake of your own, on one of our hard-won colony planets. ";
            String postText = handoverReplyCount == 1 ? longText : " ";
            responseText = postText +"Response (" + String.valueOf(++handoverReplyCount) + ")";
        }

        // pass live agent response to the chat SDK
        if(statementRequest.getCallback() != null){
            statementRequest.getCallback().onResponse(new StatementResponse(responseText, statementRequest));
        }
    }

    @Override
    public void onUrlLinkSelected(String url) {
        //if(getApplicationContext() != null) {
            // sample code for handling given link
            try {
                final Intent intent = new Intent(Intent.ACTION_VIEW).setData(Uri.parse(url));
                startActivity(intent);
            } catch (Exception e) {
                Log.w(CONVERSATION_FRAGMENT_TAG, "failed to activate link on default app: "+e.getMessage());
                Toast.makeText(this, "activating: "+url, Toast.LENGTH_SHORT).show();
            }
//        } else {
            Log.w(CONVERSATION_FRAGMENT_TAG, "got link activation while activity is no longer available.\n(" + url + ")");
        }
    //}

    @Override
    public void onChatEnded() {
        chatController.destruct();
    }

    static class MyHistoryProvider implements HistoryProvider {

        private String accountId = null;
        final Object historySync = new Object(); // in use to block multi access to history from different actions.
        private Handler handler = null;


        MyHistoryProvider() {
            handler = new Handler(Looper.getMainLooper());
        }

        private ConcurrentHashMap<String, List<HistoryElement>> chatHistory = new ConcurrentHashMap<>();
        private boolean hasHistory = false;


        @Override
        public void fetch(final int from, @FetchDirection final int direction, final HistoryListener listener) {

            new Thread(new Runnable() {
                @Override
                public void run() {
                    final List<? extends StorableChatElement> history;

                    synchronized (historySync) {
                        history = Collections.unmodifiableList(getHistoryForAccount(accountId, from, direction));
                    }

                    if (history.size() > 0) {
                        try {
                            Thread.sleep(800); // simulate async history fetching
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    if (handler.getLooper()!=null) {
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Log.d("History", "passing history list to listener, from = "+from + ", size = "+history.size());
                                hasHistory = history.size() > 0;
                                listener.onReady(from, direction, history);
                            }
                        });
                    }
                }
            }).start();
        }

        @Override
        public void store(@NonNull StorableChatElement item) {
            //if(item == null || item.getStatus() != StatusOk) return;

            synchronized (historySync) {
                ArrayList<HistoryElement> convHistory = getAccountHistory(accountId);
                convHistory.add(new HistoryElement(item));
            }
        }

        @Override
        public void remove(long timestampId) {
            synchronized (historySync) {
                ArrayList<HistoryElement> convHistory = getAccountHistory(accountId);

                Iterator<HistoryElement> iterator = convHistory.listIterator();
                while (iterator.hasNext()) {
                    HistoryElement item = iterator.next();
                    if (item.getTimestamp() == timestampId) {
                        iterator.remove();
                        break;
                    }
                }
            }
        }

        @Override
        public void update(long timestampId, int status) {

            synchronized (historySync) {
                ArrayList<HistoryElement> convHistory = getAccountHistory(accountId);

                for (HistoryElement item : convHistory) {
                    if (item.getTimestamp() == timestampId) {
                        item.status = status;
                        break;
                    }
                }
            }
        }

        @NonNull
        private ArrayList<HistoryElement> getAccountHistory(String accountId) {
            ArrayList<HistoryElement> convHistory;
            if(chatHistory.containsKey(accountId)) {
                convHistory = (ArrayList<HistoryElement>) chatHistory.get(accountId);
            } else {
                convHistory = new ArrayList<>();
                chatHistory.put(accountId,  convHistory);
            }
            return convHistory;
        }

        private List<HistoryElement> getHistoryForAccount(String account, int fromIdx, int direction) {

            List<HistoryElement> accountChatHistory = chatHistory.get(account);

            if(accountChatHistory == null)
                return new ArrayList<>();

            boolean fetchOlder = direction == Older;

            // to prevent Concurrent exception
            CopyOnWriteArrayList<HistoryElement> accountHistory = new CopyOnWriteArrayList<>(accountChatHistory);

            int historySize = accountHistory.size();

            if(fromIdx == -1) {
                fromIdx = fetchOlder ? historySize - 1: 0;
            } else if(fetchOlder){
                fromIdx = historySize - fromIdx;
            }

            int toIndex = fetchOlder ? Math.max(0, fromIdx-HistoryPageSize) :
                    Math.min(fromIdx+HistoryPageSize, historySize-1);

            try {
                Log.d("History", "fetching history items ("+ historySize +") from " +toIndex+ " to "+fromIdx);

                return accountHistory.subList(toIndex, fromIdx);

            } catch (Exception ex) {
                return new ArrayList<>();
            }
        }

    }


    //-> previous listener method signature @Override onPhoneNumberNavigation(@NonNull String phoneNumber) {
    @Override
    public void onPhoneNumberSelected(@NonNull String phoneNumber) {
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL);
            intent.setData(Uri.parse("tel:" + phoneNumber));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {

        }
    }

    @Override
    public void connectionChanged(boolean isConnected) {
        this.connectionOk = isConnected;
        if(isConnected){
           /* not supported:
		    if(pendingConversationCreation){
                chatController.createConversation(getAccount());

            } else {
                // post pending statements until disconnection
                postFailedStatements();
            }*/
        }
    }

    private void postFailedStatements() {

        for(StatementRequest request : failedStatements){
            if(!connectionOk) break; // no point of trying to re-send

            Log.d(CONVERSATION_FRAGMENT_TAG, "re-sending previously failed request: "+request.getStatement());

            if(request.getScope() instanceof StatementScope.LiveHandoverScope){
                onChatHandoverInput(request);

            } else {
                chatController.post(request);
            }

            failedStatements.remove(request);
        }
    }

    /**
     * {@link StorableChatElement} implementing class
     * sample class for app usage
     */
    static class HistoryElement implements StorableChatElement {
        byte[] key;
        protected long timestamp = 0;
        StatementScope scope;
        protected @ChatElement.Companion.ChatElementType int type;
        @StatementStatus
        int status = StatusPending;

        HistoryElement(int type, long timestamp){
            this.type = type;
            this.timestamp = timestamp;
        }

        HistoryElement(StorableChatElement storable) {
            key = storable.getStorageKey();
            type = storable.getType();
            timestamp = storable.getTimestamp();
            status = storable.getStatus();
            scope = storable.getScope();
        }

        @NonNull
        @Override
        public byte[] getStorageKey() {
            return key;
        }

        @NonNull
        @Override
        public String getStorableContent() {
            return new String(key);
        }

        @Override
        public int getType() {
            return type;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @NotNull
        @Override
        public StatementScope getScope() {
            return scope;
        }
    }

    private class MyHandoverHandler implements ChatHandler {
        @Override
        public boolean isLiveChat() {
            return false;
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public void handleEvent(@NotNull String name, @NotNull Event event) {

        }

        @Override
        public void startChat(@Nullable AccountInfo accountInfo) {

        }

        @Override
        public void endChat() {

        }

        @Override
        public void post(@NotNull ChatStatement message) {

        }

        @Override
        public void setListener(@Nullable EventListener listener) {

        }

        @Override
        public void destruct() {

        }

        @NotNull
        @Override
        public String provide(@NotNull String input) {
            return input;
        }

        @NotNull
        @Override
        public String provide(@NotNull String id, @NotNull String... formatArgs) {
            return id;
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
            startButton.setEnabled(true);
        }
    }
}
