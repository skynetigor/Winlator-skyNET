package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.Downloader;
import com.winlator.cmod.contents.RemoteDriverCatalog;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class AdrenotoolsFragment extends Fragment {
    private AdrenotoolsManager adrenotoolsManager;
    private RecyclerView recyclerView;
    private RecyclerView updatesRecyclerView;
    private UpdateAdapter updateAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
        this.adrenotoolsManager = new AdrenotoolsManager(getActivity());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ViewGroup layout = (ViewGroup)inflater.inflate(R.layout.adrenotools_fragment, container, false);
        recyclerView = layout.findViewById(R.id.RecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.setAdapter(new DriversAdapter(adrenotoolsManager.enumarateInstalledDrivers()));


        updatesRecyclerView = layout.findViewById(R.id.UpdatesRecyclerView);
        updatesRecyclerView.setLayoutManager(new LinearLayoutManager(updatesRecyclerView.getContext()));
        updateAdapter = new UpdateAdapter(new ArrayList<>());
        updatesRecyclerView.setAdapter(updateAdapter);

        layout.findViewById(R.id.BTCheckUpdates).setOnClickListener(v -> fetchLatestUpdates());

        View btInstallDriver = layout.findViewById(R.id.BTInstallDriver);
        btInstallDriver.setOnClickListener((v) -> {
            ContentDialog.confirm(getContext(), getString(R.string.install_drivers_message) + " " + getString(R.string.install_drivers_warning), () -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                getActivity().startActivityFromFragment(this, intent, MainActivity.OPEN_FILE_REQUEST_CODE);
            });
        });
        fetchLatestUpdates();
        return layout;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.adrenotools_gpu_drivers);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == MainActivity.OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            Uri uri = data.getData();
            String driver = adrenotoolsManager.installDriver(uri);
            if (!driver.isEmpty())
                ((DriversAdapter)recyclerView.getAdapter()).addItem(driver);
        }
    }

    private void fetchLatestUpdates() {
        if (updateAdapter == null || getContext() == null) return;
        final android.content.Context appContext = getContext().getApplicationContext();

        Executors.newSingleThreadExecutor().execute(() -> {
            List<RemoteDriverCatalog.Entry> catalog = RemoteDriverCatalog.load(appContext);
            List<UpdateItem> updates = new ArrayList<>();
            for (RemoteDriverCatalog.Entry entry : catalog) {
                if (updates.size() >= 5) break;
                updates.add(new UpdateItem(entry.name, entry.repository, entry.url));
            }

            runOnUi(() -> {
                updateAdapter.setItems(updates);
                if (catalog.isEmpty()) {
                    Toast.makeText(getContext(), "Connection failed or no drivers in the registry.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void downloadUpdate(UpdateItem item) {
        if (getContext() == null) return;

        File cacheDir = getContext().getCacheDir();
        Toast.makeText(getContext(), "Downloading " + item.name + "...", Toast.LENGTH_SHORT).show();

        Executors.newSingleThreadExecutor().execute(() -> {
            File tmpFile = new File(cacheDir, "driver_update.zip");
            if (tmpFile.exists()) tmpFile.delete();

            boolean success = Downloader.downloadFile(item.downloadUrl, tmpFile);
            runOnUi(() -> {
                if (!success) {
                    Toast.makeText(getContext(), "Download failed!", Toast.LENGTH_SHORT).show();
                    return;
                }

                String installedName = adrenotoolsManager.installDriver(Uri.fromFile(tmpFile));
                if (!installedName.isEmpty()) {
                    Toast.makeText(getContext(), "Installed: " + installedName, Toast.LENGTH_SHORT).show();
                    RecyclerView.Adapter adapter = recyclerView.getAdapter();
                    if (adapter instanceof DriversAdapter) {
                        ((DriversAdapter)adapter).reloadList();
                    }
                } else {
                    Toast.makeText(getContext(), "Installation failed! Invalid ZIP.", Toast.LENGTH_LONG).show();
                }
                tmpFile.delete();
            });
        });
    }

    private void runOnUi(Runnable action) {
        Activity activity = getActivity();
        if (activity != null) activity.runOnUiThread(action);
    }

    private class DriversAdapter extends RecyclerView.Adapter<DashboardViewHolder> {
        private ArrayList<String> driversList;
        public DriversAdapter(ArrayList<String> driversList) { this.driversList = driversList; }
        public void reloadList() { this.driversList = adrenotoolsManager.enumarateInstalledDrivers(); notifyDataSetChanged(); }
        @Override public DashboardViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) { return new DashboardViewHolder(LayoutInflater.from(viewGroup.getContext()).inflate(R.layout.adrenotools_dashboard_item, viewGroup, false)); }
        @Override public void onBindViewHolder(DashboardViewHolder h, final int position) {
            h.name.setText(adrenotoolsManager.getDriverName(driversList.get(position)));
            h.version.setText(adrenotoolsManager.getDriverVersion(driversList.get(position)));
            h.badge.setVisibility(View.VISIBLE);
            h.badge.setText("Current Driver");
            h.actionButton.setImageResource(android.R.drawable.ic_menu_delete);
            h.actionButton.setOnClickListener((v) -> removeAtIndex(position));
        }
        public void addItem(String item) { driversList.add(item); notifyItemInserted(getItemCount() - 1); }
        public void removeAtIndex(int index) { String deletedDriver = driversList.remove(index); adrenotoolsManager.removeDriver(deletedDriver); notifyItemRemoved(index); notifyItemRangeChanged(index, getItemCount()); }
        @Override public int getItemCount() { return driversList.size(); }
    }

    private class UpdateAdapter extends RecyclerView.Adapter<DashboardViewHolder> {
        private List<UpdateItem> items;
        UpdateAdapter(List<UpdateItem> items) { this.items = items; }
        void setItems(List<UpdateItem> items) { this.items = items; notifyDataSetChanged(); }
        @Override public DashboardViewHolder onCreateViewHolder(ViewGroup p, int v) { return new DashboardViewHolder(LayoutInflater.from(p.getContext()).inflate(R.layout.adrenotools_dashboard_item, p, false)); }
        @Override public void onBindViewHolder(DashboardViewHolder h, int position) {
            UpdateItem item = items.get(position);
            h.name.setText(item.name);
            h.version.setText(item.repoName);
            h.badge.setVisibility(View.GONE);
            h.actionButton.setImageResource(android.R.drawable.stat_sys_download);
            h.actionButton.setOnClickListener(v -> downloadUpdate(item));
        }
        @Override public int getItemCount() { return items.size(); }
    }

    private static class DashboardViewHolder extends RecyclerView.ViewHolder {
        TextView name, version, badge;
        ImageButton actionButton;
        DashboardViewHolder(View v) { super(v); name = v.findViewById(R.id.TVName); version = v.findViewById(R.id.TVVersion); badge = v.findViewById(R.id.TVBadge); actionButton = v.findViewById(R.id.BTMenu); }
    }

    private static class UpdateItem {
        String name, repoName, downloadUrl;
        UpdateItem(String name, String repoName, String downloadUrl) { this.name = name; this.repoName = repoName; this.downloadUrl = downloadUrl; }
    }
}
