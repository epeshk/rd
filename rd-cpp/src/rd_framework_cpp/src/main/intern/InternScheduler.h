#ifndef RD_CPP_INTERNSCHEDULER_H
#define RD_CPP_INTERNSCHEDULER_H

#include "IScheduler.h"

namespace rd {

    /**
     * \brief Scheduler for interning object. Maintains out of order execution.
     */
    class InternScheduler : public IScheduler {
    	static thread_local int32_t active_counts;
    public:
        //region ctor/dtor

        InternScheduler();
        //endregion

        void queue(std::function<void()> action) override;

        void flush() override;

        bool is_active() const override;
    };
}


#endif //RD_CPP_INTERNSCHEDULER_H
