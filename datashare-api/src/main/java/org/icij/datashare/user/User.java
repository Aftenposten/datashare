package org.icij.datashare.user;

import java.util.Objects;

public class User {
    public final String id;

    public User(final String id) { this.id = id;}

    public String indexName() { return id + "-datashare";}
    public String getPath() { return this.equals(local()) ? "": id;}
    public boolean isNull() { return this.id == null;}

    public static User local() { return new User("local");}
    public static User nullUser() { return new User(null);}
    @Override public int hashCode() { return Objects.hash(id);}
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof User)) return false;
        User user = (User) o;
        return Objects.equals(id, user.id);
    }
}
