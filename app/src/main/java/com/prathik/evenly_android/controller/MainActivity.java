package com.prathik.evenly_android.controller;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.prathik.evenly_android.R;
import com.prathik.evenly_android.controller.expense.AddExpenseActivity;

public class MainActivity extends AppCompatActivity {

    private MaterialToolbar topAppBar;
    private BottomNavigationView bottomNav;

    private FloatingActionButton fabAddExpense;
    private String currentTab = "GROUPS"; // GROUPS / FRIENDS / ACTIVITY / ACCOUNT

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        topAppBar = findViewById(R.id.topAppBar);
        bottomNav = findViewById(R.id.bottom_navigation);
        fabAddExpense = findViewById(R.id.fab_add_expense);

        // FAB click -> open AddExpenseActivity with context
        fabAddExpense.setOnClickListener(v -> {
            Intent i = new Intent(this, AddExpenseActivity.class);

            if ("FRIENDS".equals(currentTab)) {
                i.putExtra(AddExpenseActivity.EXTRA_CONTEXT_TYPE, AddExpenseActivity.CONTEXT_FRIEND);
            } else {
                i.putExtra(AddExpenseActivity.EXTRA_CONTEXT_TYPE, AddExpenseActivity.CONTEXT_GROUP);
            }

            startActivity(i);
        });

        // 1) Handle toolbar icon clicks
        topAppBar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();

            // Groups actions
            if (id == R.id.action_add_group) {
                Toast.makeText(this, "Add Group (next step)", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (id == R.id.action_search_groups) {
                Toast.makeText(this, "Search Groups (next step)", Toast.LENGTH_SHORT).show();
                return true;
            }

            // Friends actions
            if (id == R.id.action_add_friend) {
                Toast.makeText(this, "Add Friend (next step)", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (id == R.id.action_search_friends) {
                Toast.makeText(this, "Search Friends (next step)", Toast.LENGTH_SHORT).show();
                return true;
            }

            // Activity actions
            if (id == R.id.action_search_activity) {
                Toast.makeText(this, "Search Activity (next step)", Toast.LENGTH_SHORT).show();
                return true;
            }

            return false;
        });

        // 2) Default tab = Groups
        bottomNav.setSelectedItemId(R.id.nav_groups);
        currentTab = "GROUPS";
        switchTopBarMenu(R.id.nav_groups);
        setFabVisibilityForTab(R.id.nav_groups);
        loadFragment(new GroupsFragment());

        // 3) Bottom nav switching
        bottomNav.setOnItemSelectedListener(item -> {
            int tabId = item.getItemId();

            switchTopBarMenu(tabId);
            setFabVisibilityForTab(tabId);

            if (tabId == R.id.nav_groups) {
                currentTab = "GROUPS";
                loadFragment(new GroupsFragment());
            } else if (tabId == R.id.nav_friends) {
                currentTab = "FRIENDS";
                loadFragment(new FriendsFragment());
            } else if (tabId == R.id.nav_activity) {
                currentTab = "ACTIVITY";
                loadFragment(new ActivityFragment());
            } else if (tabId == R.id.nav_account) {
                currentTab = "ACCOUNT";
                loadFragment(new AccountFragment());
            }

            return true;
        });
    }

    private void setFabVisibilityForTab(int tabId) {
        // FAB only for Groups and Friends
        if (tabId == R.id.nav_groups || tabId == R.id.nav_friends) {
            fabAddExpense.setVisibility(View.VISIBLE);
        } else {
            fabAddExpense.setVisibility(View.GONE);
        }
    }

    private void switchTopBarMenu(int tabId) {
        topAppBar.getMenu().clear();

        if (tabId == R.id.nav_groups) {
            topAppBar.inflateMenu(R.menu.toolbar_groups);
        } else if (tabId == R.id.nav_friends) {
            topAppBar.inflateMenu(R.menu.toolbar_friends);
        } else if (tabId == R.id.nav_activity) {
            topAppBar.inflateMenu(R.menu.toolbar_activity);
        } else if (tabId == R.id.nav_account) {
            topAppBar.inflateMenu(R.menu.toolbar_account); // empty
        }
    }

    private void loadFragment(@NonNull Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}