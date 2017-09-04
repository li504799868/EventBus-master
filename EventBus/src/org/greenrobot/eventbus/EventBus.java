/*
 * Copyright (C) 2012-2016 Markus Junginger, greenrobot (http://greenrobot.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.greenrobot.eventbus;

import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * EventBus is a central publish/subscribe event system for Android. Events are posted ({@link #post(Object)}) to the
 * bus, which delivers it to subscribers that have a matching handler method for the event type. To receive events,
 * subscribers must register themselves to the bus using {@link #register(Object)}. Once registered, subscribers
 * receive events until {@link #unregister(Object)} is called. Event handling methods must be annotated by
 * {@link Subscribe}, must be public, return nothing (void), and have exactly one parameter
 * (the event).
 *
 * @author Markus Junginger, greenrobot
 */
public class EventBus {

    /** Log tag, apps may override it. */
    public static String TAG = "EventBus";

    static volatile EventBus defaultInstance;

    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();
    /***/
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<>();

    /**
     * 这里保存的是EventType 和 绑定的类和方法等信息
     * */
    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    /**
     * 保存了Class和绑定的Event列表
     * */
    private final Map<Object, List<Class<?>>> typesBySubscriber;
    private final Map<Class<?>, Object> stickyEvents;

    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };

    private final HandlerPoster mainThreadPoster;
    private final BackgroundPoster backgroundPoster;
    private final AsyncPoster asyncPoster;
    /**
     * 这个类用来查找Class和对应的被注解方法
     * */
    private final SubscriberMethodFinder subscriberMethodFinder;
    private final ExecutorService executorService;

    /**
     * 是否需要抛出注解的异常
     * */
    private final boolean throwSubscriberException;
    /**
     * 是否打印注解的异常
     * */
    private final boolean logSubscriberExceptions;
    /**
     * 是否打印没有这个注解的信息
     * */
    private final boolean logNoSubscriberMessages;
    /**
     * 是否发出注解的异常Event
     * */
    private final boolean sendSubscriberExceptionEvent;
    /**
     * 是否发出没有这个注解的异常Event
     * */
    private final boolean sendNoSubscriberEvent;
    /**
     * 判断StickyEvent是否可以被继承
     *
     */
    private final boolean eventInheritance;

    private final int indexCount;

    /** Convenience singleton for apps using a process-wide EventBus instance. */
    /**
     * 使用单例模式
     * */
    public static EventBus getDefault() {
        if (defaultInstance == null) {
            synchronized (EventBus.class) {
                if (defaultInstance == null) {
                    defaultInstance = new EventBus();
                }
            }
        }
        return defaultInstance;
    }

    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /** For unit test primarily. */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        subscriptionsByEventType = new HashMap<>();
        typesBySubscriber = new HashMap<>();
        stickyEvents = new ConcurrentHashMap<>();
        mainThreadPoster = new HandlerPoster(this, Looper.getMainLooper(), 10);
        backgroundPoster = new BackgroundPoster(this);
        asyncPoster = new AsyncPoster(this);
        indexCount = builder.subscriberInfoIndexes != null ? builder.subscriberInfoIndexes.size() : 0;
        subscriberMethodFinder = new SubscriberMethodFinder(builder.subscriberInfoIndexes,
                builder.strictMethodVerification, builder.ignoreGeneratedIndex);
        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }

    /**
     * Registers the given subscriber to receive events. Subscribers must call {@link #unregister(Object)} once they
     * are no longer interested in receiving events.
     * <p/>
     * Subscribers have event handling methods that must be annotated by {@link Subscribe}.
     * The {@link Subscribe} annotation also allows configuration like {@link
     * ThreadMode} and priority.
     */
    /**
     * 这里来注册广播，并且提示接收Event的方法必须被Subscribe注解
     * */
    public void register(Object subscriber) {
        // 先得到类名
        Class<?> subscriberClass = subscriber.getClass();
        // 找到这类被注解的方法
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriberClass);
        synchronized (this) {
            // 遍历查找到的被注解的方法
            for (SubscriberMethod subscriberMethod : subscriberMethods) {
                // 把注册的类和被注解的方法保存起来
                subscribe(subscriber, subscriberMethod);
            }
        }
    }

    // Must be called in synchronized block
    /**
     * 把注册的类和对应的被注解的方法保存起来
     * */
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod) {
        // 得到对应的Event，也就是参数的类型
        Class<?> eventType = subscriberMethod.eventType;
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod);
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        // 增加了一个判空，添加在到subscriptionsByEventType中
        if (subscriptions == null) {
            subscriptions = new CopyOnWriteArrayList<>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            // 增加不允许重复的判断
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }

        // 遍历对应的subscriptions
        int size = subscriptions.size();
        // 根据设置的方法的优先级进行排列
        for (int i = 0; i <= size; i++) {
            if (i == size || subscriberMethod.priority > subscriptions.get(i).subscriberMethod.priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }

        // 这里把注册的类保存了起来，最终还要从typesBySubscriber解绑
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        if (subscribedEvents == null) {
            subscribedEvents = new ArrayList<>();
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        // 把绑定的EventType放入list中
        subscribedEvents.add(eventType);

        // 判断是否是StickyEvent，带有上一次缓存的广播
        if (subscriberMethod.sticky) {
            // 如果StickyEvent可以被继承
            if (eventInheritance) {
                // Existing sticky events of all subclasses of eventType have to be considered.
                // Note: Iterating over all events may be inefficient with lots of sticky events,
                // thus data structure should be changed to allow a more efficient lookup
                // (e.g. an additional map storing sub classes of super classes: Class -> List<Class>).
                // 找到StickyEvent的Set集合
                Set<Map.Entry<Class<?>, Object>> entries = stickyEvents.entrySet();
                // 遍历集合中的元素
                for (Map.Entry<Class<?>, Object> entry : entries) {
                    // 得到注册StickyEvent的Class
                    Class<?> candidateEventType = entry.getKey();
                    // 判断是否是集成关系
                    if (eventType.isAssignableFrom(candidateEventType)) {
                        // 得到Class对应的Event
                        Object stickyEvent = entry.getValue();
                        // 检查是否是要给当前注册的类，返回StickyEvent
                        // 也就是注册了之后，仍然可以得到之前缓存的StickyEvent
                        checkPostStickyEventToSubscription(newSubscription, stickyEvent);
                    }
                }
            } else {
                Object stickyEvent = stickyEvents.get(eventType);
                checkPostStickyEventToSubscription(newSubscription, stickyEvent);
            }
        }
    }

    /**
     * 检查是否是要给当前注册的类，返回StickyEvent
     * */
    private void checkPostStickyEventToSubscription(Subscription newSubscription, Object stickyEvent) {
        // 判断之前是否有缓存
        if (stickyEvent != null) {
            // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
            // --> Strange corner case, which we don't take care of here.
            // 响应之前的StickyEvent
            postToSubscription(newSubscription, stickyEvent, Looper.getMainLooper() == Looper.myLooper());
        }
    }

    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /** Only updates subscriptionsByEventType, not typesBySubscriber! Caller must update typesBySubscriber. */
    /**
     * 移除对象绑定的EventType
     * */
    private void unsubscribeByEventType(Object subscriber, Class<?> eventType) {
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                if (subscription.subscriber == subscriber) {
                    subscription.active = false;
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /** Unregisters the given subscriber from all event classes. */
    /**
     * 解绑注册
     * */
    public synchronized void unregister(Object subscriber) {
        // 获取对象的被注解的方法
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        if (subscribedTypes != null) {
            // 先循环移除绑定的Event
            for (Class<?> eventType : subscribedTypes) {
                unsubscribeByEventType(subscriber, eventType);
            }
            // 解绑
            typesBySubscriber.remove(subscriber);
        } else {
            Log.w(TAG, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }

    /** Posts the given event to the event bus. */
    /**
     *  发送Event
     * */
    public void post(Object event) {
        // 通过工厂类，获取一个执行状态类
        // 每一个线程对应一个
        PostingThreadState postingState = currentPostingThreadState.get();
        // 把Event加入到队列中
        List<Object> eventQueue = postingState.eventQueue;
        eventQueue.add(event);

        // 如果这个信息类并没有在执行
        if (!postingState.isPosting) {
            // 是否是主线程
            postingState.isMainThread = Looper.getMainLooper() == Looper.myLooper();
            // 改变执行状态
            postingState.isPosting = true;
            // 判断执行是否被取消了
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            // 开始循环发送Event
            try {
                while (!eventQueue.isEmpty()) {
                    // 别忘了ArrayList的remove会返回remove的对象
                    // 执行队列中第一个Event
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                // 执行结束之后，要重置状态类的属性
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }

    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link Subscribe#priority()}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#POSTING}.
     */
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.POSTING) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access by subscribers using {@link Subscribe#sticky()}.
     */
    /**
     * 发送StickyEvent
     * */
    public void postSticky(Object event) {
        // 这个把Event进行了缓存
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        //  和post的执行是完全一样的
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events.
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 执行单个Event
     * */
    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        // 得到Event的Class
        Class<?> eventClass = event.getClass();
        boolean subscriptionFound = false;
        // 如果Event是支持继承的
        if (eventInheritance) {
            // 找到使用Event的父类
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                // 开始发送Event给当前的Class
                // 每一次都要跟下一次的结果进行 或操作，也就是只要有一个Class的Event没找到，都会返回false
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
            // 开始发送Event给当前的Class
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        //如果并没有找到这个Event绑定的信息
        if (!subscriptionFound) {
            // 是否打印log
            if (logNoSubscriberMessages) {
                Log.d(TAG, "No subscribers registered for event " + eventClass);
            }
            // 是否发送Event
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                post(new NoSubscriberEvent(this, event));
            }
        }
    }

    /**
     *  根据Event的类型发送给对一个的Class
     * */
    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            // 得到和Event对应的类和方法
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        // 判空
        if (subscriptions != null && !subscriptions.isEmpty()) {
            // 对列表循环
            for (Subscription subscription : subscriptions) {
                // 设置要执行的信息
                postingState.event = event;
                postingState.subscription = subscription;
                boolean aborted = false;
                try {
                    // 执行Class中被注解的方法
                    postToSubscription(subscription, event, postingState.isMainThread);
                    // 如果post被取消了，停止循环
                    aborted = postingState.canceled;
                } finally {
                    // 重置执行的信息
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 根据不同的ThreadMode，回调被绑定的方法
     * */
    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        // 响应的线程
        switch (subscription.subscriberMethod.threadMode) {
            // 和调用的线程相同
            case POSTING:
                // 调用被注解的方法
                invokeSubscriber(subscription, event);
                break;
            // 主线程
            case MAIN:
                // 如果是主线程，直接调用被注解的方法
                if (isMainThread) {
                    invokeSubscriber(subscription, event);
                } else {
                    // 通过Handler机制，在主线程中调用被注解的方法
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            // 后台线程，如果当前不是主线程，不会切换线程
            case BACKGROUND:
                // 如果是主线程，交给后台线程进行排队处理
                if (isMainThread) {
                    backgroundPoster.enqueue(subscription, event);
                } else {
                    // 直接调用被注解的方法
                    invokeSubscriber(subscription, event);
                }
                break;
            // 异步线程，新建线程
            case ASYNC:
                // 异步线程处理
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /** Looks up all Class objects including super classes and interfaces. Should also work for interfaces. */
    /**
     * 找到所有要使用这个Event的Class列表
     * */
    private static List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            // 先从缓存中获取
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                eventTypes = new ArrayList<>();
                Class<?> clazz = eventClass;
                // 开始循环添加使用这个Event的Class
                while (clazz != null) {
                    eventTypes.add(clazz);
                    // 添加自己的父类
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    clazz = clazz.getSuperclass();
                }
                // 放入缓存
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /** Recurses through super interfaces. */
    /**
     * 添加自己的父类到列表中
     * */
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }

    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     */
    /**
     * 得到队列中的PendingPost，执行携带的Subscription
     * */
    void invokeSubscriber(PendingPost pendingPost) {
        Object event = pendingPost.event;
        Subscription subscription = pendingPost.subscription;
        // 回收利用PendingPostPendingPost
        PendingPost.releasePendingPost(pendingPost);
        // 判断Subscription是否处于激活状态
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    /**
     * 通过反射的方法，调用被注解的方法
     * */
    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            // 处理反射调用出现的异常
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    /**
     * 处理过程中发生的异常
     * */
    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                Log.e(TAG, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                Log.e(TAG, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                Log.e(TAG, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);
            }
        }
    }

    /** For ThreadLocal, much faster to set (and get multiple values). */
    /**
     * 保存了执行Event相关的信息
     * */
    final static class PostingThreadState {
        final List<Object> eventQueue = new ArrayList<Object>();
        boolean isPosting;
        boolean isMainThread;
        Subscription subscription;
        Object event;
        boolean canceled;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

    @Override
    public String toString() {
        return "EventBus[indexCount=" + indexCount + ", eventInheritance=" + eventInheritance + "]";
    }
}
