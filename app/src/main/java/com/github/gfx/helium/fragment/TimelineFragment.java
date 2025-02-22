package com.github.gfx.helium.fragment;

import com.bumptech.glide.Glide;
import com.cookpad.android.rxt4a.operators.OperatorAddToCompositeSubscription;
import com.cookpad.android.rxt4a.schedulers.AndroidSchedulers;
import com.cookpad.android.rxt4a.subscriptions.AndroidCompositeSubscription;
import com.github.gfx.android.orma.TransactionTask;
import com.github.gfx.helium.HeliumApplication;
import com.github.gfx.helium.R;
import com.github.gfx.helium.api.HatenaClient;
import com.github.gfx.helium.databinding.CardTimelineEntryBinding;
import com.github.gfx.helium.databinding.FragmentEntryBinding;
import com.github.gfx.helium.model.HatebuEntry;
import com.github.gfx.helium.model.HatebuEntry_Relation;
import com.github.gfx.helium.model.OrmaDatabase;
import com.github.gfx.helium.util.AppTracker;
import com.github.gfx.helium.util.LoadingAnimation;
import com.github.gfx.helium.util.ViewSwitcher;
import com.github.gfx.helium.widget.ArrayRecyclerAdapter;
import com.github.gfx.helium.widget.BindingHolder;
import com.github.gfx.helium.widget.LayoutManagers;
import com.github.gfx.helium.widget.LoadingIndicatorViewHolder;
import com.github.gfx.helium.widget.OnItemClickListener;
import com.github.gfx.helium.widget.OnItemLongClickListener;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;
import javax.inject.Inject;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

/**
 * The timeline that shows what you like.
 */
@ParametersAreNonnullByDefault
public class TimelineFragment extends Fragment
        implements OnItemClickListener<HatebuEntry>, OnItemLongClickListener<HatebuEntry> {

    static final String TAG = TimelineFragment.class.getSimpleName();

    static final String kUsername = "username";

    static final int CACHED_ENTRY_SIZE = 100;

    final HatebuEntry emptyEntry = new HatebuEntry();

    @Inject
    HatenaClient hatenaClient;

    @Inject
    AppTracker tracker;

    @Inject
    AndroidCompositeSubscription compositeSubscription;

    @Inject
    ViewSwitcher viewSwitcher;

    @Inject
    LoadingAnimation loadingAnimation;

    @Inject
    LayoutManagers layoutManagers;

    @Inject
    OrmaDatabase orma;

    FragmentEntryBinding binding;

    EntriesAdapter adapter;

    String username;

    int currentEntries;

    private List<HatebuEntry> cachedEntries = Collections.emptyList();

    public TimelineFragment() {
    }

    public static TimelineFragment newInstance(String username) {
        TimelineFragment fragment = new TimelineFragment();
        Bundle args = new Bundle();
        args.putString(kUsername, username);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);

        HeliumApplication.getComponent(this).inject(this);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        adapter = new EntriesAdapter(getActivity());
        adapter.setOnItemClickListener(this);
        adapter.setOnItemLongClickListener(this);

        username = getArguments().getString(kUsername);
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentEntryBinding.inflate(inflater, container, false);

        loadCachedEntries();

        binding.list.setAdapter(adapter);
        binding.list.setLayoutManager(layoutManagers.create());

        binding.swipeRefresh.setColorSchemeResources(R.color.app_primary);
        binding.swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                reload().subscribe(new Action1<List<HatebuEntry>>() {
                    @Override
                    public void call(List<HatebuEntry> items) {
                        mergeItemsAndCache(items);
                        binding.swipeRefresh.setRefreshing(false);
                    }
                });

            }
        });

        reload().subscribe(new Action1<List<HatebuEntry>>() {
            @Override
            public void call(List<HatebuEntry> items) {
                mergeItemsAndCache(items);

                binding.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                        if (!recyclerView.canScrollVertically(1)) {
                            loadMore();
                        }
                    }
                });
            }
        });

        return binding.getRoot();
    }

    @Override
    public void onStop() {
        compositeSubscription.unsubscribe();

        truncateCache();

        super.onStop();
    }

    void truncateCache() {
        if (!cachedEntries.isEmpty()) {
            relation().truncateAsObservable(CACHED_ENTRY_SIZE)
                    .subscribeOn(Schedulers.io())
                    .subscribe();
        }
    }

    void mergeItemsAndCache(final List<HatebuEntry> newItems) {
        int i;
        FIND: for (i = 0; i < newItems.size(); i++) {
            HatebuEntry newItem = newItems.get(i);
            for (HatebuEntry cache : adapter) {
                if (newItem.link.equals(cache.link) && newItem.creator.equals(cache.creator)) {
                    break FIND;
                }
            }
        }

        if (i == 0) {
            return; // nothing to do
        }

        final List<HatebuEntry> items = newItems.subList(0, i);

        orma.transactionAsync(new TransactionTask() {
            @Override
            public void execute() throws Exception {
                List<HatebuEntry> reversedItems = new ArrayList<>(items);
                Collections.reverse(reversedItems);
                relation().inserter()
                        .executeAll(reversedItems);
            }
        });

        if (cachedEntries.isEmpty()) {
            adapter.reset(items);
        } else {
            adapter.addAll(0, items);
            adapter.notifyItemRangeInserted(0, items.size());
        }
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);

        if (isVisibleToUser) {
            tracker.sendScreenView(TAG);
        }
    }

    HatebuEntry_Relation relation() {
        return orma.relationOfHatebuEntry()
                .orderByCacheIdDesc();
    }

    void loadCachedEntries() {
        cachedEntries = relation().selector().toList();

        if (cachedEntries.isEmpty()) {
            for (int i = 0, max = layoutManagers.getSpanCount(); i < max; i++) {
                adapter.addItem(emptyEntry);
            }
        } else {
            adapter.addAll(cachedEntries);
        }
    }

    Observable<List<HatebuEntry>> reload() {
        currentEntries = 0;
        Observable<List<HatebuEntry>> observable = hatenaClient.getFavotites(username, currentEntries);
        return observable
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .lift(new OperatorAddToCompositeSubscription<List<HatebuEntry>>(compositeSubscription))
                .onErrorReturn(new Func1<Throwable, List<HatebuEntry>>() {
                    @Override
                    public List<HatebuEntry> call(Throwable e) {
                        reportError(e);
                        return Collections.emptyList();
                    }
                });
    }

    void loadMore() {
        currentEntries += adapter.getItemCount();
        hatenaClient.getFavotites(username, currentEntries)
                .observeOn(AndroidSchedulers.mainThread())
                .lift(new OperatorAddToCompositeSubscription<List<HatebuEntry>>(compositeSubscription))
                .subscribe(new Subscriber<List<HatebuEntry>>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {
                        reportError(e);
                    }

                    @Override
                    public void onNext(List<HatebuEntry> items) {
                        adapter.addAllWithNotification(items);
                    }
                });
    }

    void reportError(Throwable e) {
        Log.w(TAG, "Error while loading entries", e);
        if (getActivity() != null) {
            Toast.makeText(getActivity(), "Error while loading entries\n"
                            + e.getLocalizedMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onItemClick(@NonNull View view, @NonNull HatebuEntry entry) {
        openUri(Uri.parse(entry.link), "original");
    }

    @Override
    public boolean onItemLongClick(@NonNull View view, @NonNull HatebuEntry entry) {
        openUri(hatenaClient.buildHatebuEntryUri(entry.link), "service");
        return true;
    }

    void openUri(Uri uri, String action) {
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        startActivity(intent);
        trackOpenUri(action);
    }

    void trackOpenUri(String action) {
        tracker.sendEvent(TAG, action);
    }

    private class EntriesAdapter extends ArrayRecyclerAdapter<HatebuEntry, BindingHolder<CardTimelineEntryBinding>> {

        static final int TYPE_LOADING = 0;

        static final int TYPE_NORMAL = 1;

        public EntriesAdapter(@NonNull Context context) {
            super(context);
        }

        @Override
        public int getItemViewType(int position) {
            HatebuEntry entry = getItem(position);
            return entry == emptyEntry ? TYPE_LOADING : TYPE_NORMAL;
        }

        @Override
        public BindingHolder<CardTimelineEntryBinding> onCreateViewHolder(ViewGroup parent, int viewType) {
            switch (viewType) {
                case TYPE_LOADING:
                    return new LoadingIndicatorViewHolder<>(getContext(), parent, R.layout.card_timeline_entry);
                case TYPE_NORMAL:
                    return new BindingHolder<>(getContext(), parent, R.layout.card_timeline_entry);
            }
            throw new AssertionError("not reached");
        }

        @Override
        public void onBindViewHolder(final BindingHolder<CardTimelineEntryBinding> holder, final int position) {
            CardTimelineEntryBinding binding = holder.binding;

            final HatebuEntry entry = getItem(position);

            if (entry == emptyEntry) {
                return;
            }

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dispatchOnItemClick(v, entry);
                }
            });
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    return dispatchOnItemLongClick(v, entry);
                }
            });

            binding.bookmarkCount.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dispatchOnItemLongClick(v, entry);
                }
            });

            Glide.with(getContext())
                    .load(hatenaClient.buildHatebuIconUri(entry.creator))
                    .into(binding.author);

            binding.title.setText(entry.title);

            viewSwitcher.setTextOrGone(binding.tags, entry.getTags());
            viewSwitcher.setTextOrGone(binding.comment, entry.description);

            binding.date.setText(entry.getTimestamp());
            binding.bookmarkCount.setText(entry.bookmarkCount);
            binding.originalUrl.setText(entry.link);

            CharSequence summary = entry.getSummary();
            if (!TextUtils.isEmpty(summary)) {
                binding.textSummary.setText(entry.getSummary());
                binding.layoutSummary.setVisibility(View.VISIBLE);
                binding.textSummary.setVisibility(View.VISIBLE);
                binding.imageSummary.setVisibility(View.GONE);
            } else if (entry.looksLikeImageUrl()) {
                Glide.with(getContext())
                        .load(entry.link)
                        .into(binding.imageSummary);
                binding.layoutSummary.setVisibility(View.VISIBLE);
                binding.textSummary.setVisibility(View.GONE);
                binding.imageSummary.setVisibility(View.VISIBLE);
            } else {
                binding.layoutSummary.setVisibility(View.GONE);
            }
        }
    }
}
