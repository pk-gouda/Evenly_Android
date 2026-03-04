package com.prathik.evenly_android;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import android.content.Intent;
import android.widget.Toast;


import com.google.android.material.appbar.MaterialToolbar;

public class MainActivity extends AppCompatActivity {

    private MaterialToolbar topAppBar;
    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        topAppBar = findViewById(R.id.topAppBar);
        bottomNav = findViewById(R.id.bottom_navigation);

        // 1) Handle toolbar icon clicks
        topAppBar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();

            // Groups actions
            if (id == R.id.action_add_group) {
                // next: open CreateGroupActivity
                Toast.makeText(this, "Add Group (next step)", Toast.LENGTH_SHORT).show();
                return true;
            }
            if (id == R.id.action_search_groups) {
                Toast.makeText(this, "Search Groups (next step)", Toast.LENGTH_SHORT).show();
                return true;
            }

            // Friends actions
            if (id == R.id.action_add_friend) {
                // next: open AddFriendActivity
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
        switchTopBarMenu(R.id.nav_groups);
        loadFragment(new GroupsFragment());

        // 3) Bottom nav switching
        bottomNav.setOnItemSelectedListener(item -> {
            int tabId = item.getItemId();

            switchTopBarMenu(tabId);

            if (tabId == R.id.nav_groups) loadFragment(new GroupsFragment());
            else if (tabId == R.id.nav_friends) loadFragment(new FriendsFragment());
            else if (tabId == R.id.nav_activity) loadFragment(new ActivityFragment());
            else if (tabId == R.id.nav_account) loadFragment(new AccountFragment());

            return true;
        });
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