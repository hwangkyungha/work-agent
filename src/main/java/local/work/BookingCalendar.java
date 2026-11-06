package local.work;

interface BookingCalendar {
    String create(String reservationId, GroupwareBooking.Request request, String room) throws Exception;
    void delete(String reservationId) throws Exception;
}
