package io.errs;

public class DeleteFileException extends Exception {
    private boolean pckage;

    public DeleteFileException(boolean pckage) {
        super("This exception is used to tell the preprocessor environment to delete a file... " +
                "this shouldn't be allowed to escape uncaptured.");

        this.pckage = pckage;
    }

    public boolean isDeleteFullPackage() {
        return pckage;
    }
}
