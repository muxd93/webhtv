package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;

import java.util.Objects;

public class HomeMore {

    public enum Kind {
        KEEP
    }

    private final Kind kind;
    private final int count;

    public HomeMore(Kind kind, int count) {
        this.kind = kind;
        this.count = count;
    }

    public Kind getKind() {
        return kind;
    }

    public int getCount() {
        return count;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HomeMore more = (HomeMore) o;
        return count == more.count && kind == more.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, count);
    }

    @NonNull
    @Override
    public String toString() {
        return "HomeMore{" + "kind=" + kind + ", count=" + count + '}';
    }
}
